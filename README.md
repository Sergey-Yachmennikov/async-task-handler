# Async Task Handler

A distributed asynchronous task execution service: tasks arrive from Kafka, are
stored in PostgreSQL and executed by a worker pool. Multiple instances share one
database and split the queue between themselves without processing anything twice.

## How it works

Data flow diagram: [`docs/data-flow.drawio`](docs/data-flow.drawio) — opens in
[diagrams.net](https://app.diagrams.net) or with the draw.io plugin for
VS Code / IntelliJ IDEA.

```
POST /api/tasks ───┐
                   ├──► Kafka topic ──► consumer ──► DB (NEW)
external producer ─┘         │        (deduplication)  │
                             │                         ▼
                     unparseable /       scheduler: FOR UPDATE SKIP LOCKED
                     failed validation                 │
                             │                         ▼
                             ▼            worker pool ──► IN_PROGRESS
                     topic tasks.DLT                   │  progress 0..100
                                                       ▼
                                            COMPLETED | FAILED
                                                       │
                                    GET /api/tasks/{id} ◄┘

          stuck in IN_PROGRESS ──► recovery ──► NEW or FAILED
```

1. A task reaches the topic either through `POST /api/tasks` or from any external
   producer. The controller never writes to the database, so a task follows the
   same path regardless of where it came from.
2. The consumer validates the message, discards redelivered duplicates and stores
   the task with status `NEW`. Unrecoverable messages go to `tasks.DLT`.
3. Every `poll-interval-ms` the scheduler claims as many tasks as there are free
   threads in the pool and moves them to `IN_PROGRESS`.
4. A worker simulates the work, saving progress along the way, and finishes the
   task as `COMPLETED` or `FAILED`.
5. A separate check brings back tasks left stranded by a crashed instance.
6. The current state is available at any moment via `GET /api/tasks/{id}`.

### Claiming tasks

The central query lives in `TaskRepository.lockNewTasks`:

```sql
SELECT * FROM tasks
WHERE status = 'NEW'
ORDER BY id
LIMIT :limit
FOR UPDATE SKIP LOCKED
```

`SKIP LOCKED` makes PostgreSQL skip rows already locked by another transaction
instead of waiting on them. Concurrent workers — including those in different
instances — receive disjoint sets of tasks with no conflicts and no retries. The
claiming transaction lasts a few milliseconds: execution happens outside it and
holds no database locks.

The entity also carries `@Version`, which guards the status-update path where
another party could in principle touch the same task.

## Reliability

### Stuck tasks

An instance can crash after moving a task to `IN_PROGRESS` but before writing the
final status. Nobody is executing that task any more, and the scheduler only picks
up tasks in status `NEW` — without a dedicated check the row would stay
`IN_PROGRESS` forever.

`StuckTaskRecoveryService` looks for tasks whose `started_at` is too old. If the
retry budget is not exhausted, the task goes back to `NEW` with `retry_count`
incremented and traces of the previous attempt cleared; otherwise it is marked
as failed.

**`stuck-timeout-ms` must exceed the maximum allowed task duration** (600,000 ms).
Otherwise recovery will take a task away from a live worker that is legitimately
executing it, and the task will run twice.

### Idempotency

Kafka guarantees at-least-once delivery: after a consumer failure or a group
rebalance a message will arrive again. The message key is stored in `dedup_key`,
and redelivered messages are dropped.

The protection has two layers: a check before the insert filters out obvious
duplicates, and a unique constraint in the database closes the race where two
instances check simultaneously and both see that the task does not exist yet.
There is always a window between the check and the insert, so the check alone
is not enough.

A message without a key has nothing to deduplicate on — such a task is stored
as is.

### Unrecoverable messages

A message that fails validation, or does not parse at all, is published to
`tasks.DLT` and removed from the queue. Redelivering it is pointless — the body
will not change on retry — and it must not stay at the head of the partition,
which would block everything behind it.

Transient failures (an unavailable database, for instance) are retried twice with
a one-second pause and only then sent to the DLT.

## Stack

| Component | Version |
|---|---|
| Spring Boot | 4.1.1 (Framework 7.0.9) |
| Java | 21 |
| PostgreSQL | 17 |
| Apache Kafka | 4.2.1, KRaft (no ZooKeeper) |
| Liquibase | 5.0.3 |
| MapStruct | 1.6.3 |
| springdoc-openapi | 3.1.1 |
| Testcontainers | 2.0.5 |

## Running

```bash
docker compose up -d --build
```

This brings up PostgreSQL, Kafka, kafka-ui and the service itself. Check
readiness with:

```bash
docker compose ps
```

| Service | Address |
|---|---|
| REST API | http://localhost:8080/api/tasks |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |
| Metrics | http://localhost:8080/actuator/prometheus |
| kafka-ui | http://localhost:8090 |

### Connection settings

Credentials and ports live in [`.env`](.env), which Compose reads automatically:

```dotenv
POSTGRES_DB=taskdb
POSTGRES_USER=taskuser
POSTGRES_PASSWORD=taskpass
POSTGRES_PORT=5432
KAFKA_EXTERNAL_PORT=29092
APP_PORTS=8080-8085
KAFKA_UI_PORT=8090
```

Compose passes them to the application as `DB_HOST`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD` and `KAFKA_BOOTSTRAP_SERVERS`. There is no Spring profile for
Docker: the same image is configured entirely from the outside.

`application.yaml` declares defaults for every one of those variables, so
running from the host without any environment set still works against the
Compose infrastructure.

The values committed here are development defaults. In a real deployment this
file holds real credentials, belongs in `.gitignore`, and the secrets come from
the orchestrator instead.

### Multiple instances

```bash
docker compose up -d --build --scale app=3
```

Instances take ports from the `8080-8085` range. The exact numbers **depend on
what is free**: when recreating running containers Docker picks the next
available ports in the range, so after a restart the service may end up on 8083
instead of 8080. `docker compose ps` always shows the current addresses.

To see how the instances split the queue:

```sql
SELECT worker_id, count(*) FROM tasks GROUP BY worker_id;
```

`worker_id` is filled in at claim time, so it shows which instance executed
each task.

## API

### Submit a task

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"name":"generate-report","durationMs":5000}'
```

```json
{ "message": "Задача принята в обработку", "correlationKey": "3f2b9c1e-..." }
```

The response is `202` rather than `201`: the message has only been placed in
Kafka, there is no database row yet — which is also why the response carries no
task id.

### Check the state

```bash
curl http://localhost:8080/api/tasks/1
```

```json
{
  "id": 1,
  "name": "generate-report",
  "durationMs": 5000,
  "status": "IN_PROGRESS",
  "progress": 40,
  "result": null,
  "errorMessage": null,
  "workerId": "31049e99b94c-6dcd7127",
  "createdAt": "2026-09-07T07:43:38.001Z",
  "startedAt": "2026-09-07T07:43:38.075Z",
  "finishedAt": null
}
```

### Response codes

| Code | When |
|---|---|
| 200 | Task found |
| 202 | Task accepted for processing |
| 400 | Validation error, non-numeric or non-positive id, unreadable body |
| 404 | Task not found |
| 500 | Unexpected error |

The error body has the same shape for every code: `timestamp`, `status`,
`error`, `message`, `path` and `violations` — the last one only for validation
errors.

### Publishing straight to Kafka

Through kafka-ui (http://localhost:8090) into the `tasks` topic:

```json
{ "name": "from-kafka", "durationMs": 3000 }
```

No type headers are needed: the consumer is configured with a fixed message
type, so hand-written JSON is read exactly like messages sent through
`POST /api/tasks`. The message key is used for deduplication.

## Metrics

Beyond the standard ones, `/actuator/prometheus` exposes:

| Metric | Meaning |
|---|---|
| `tasks_registered_total` | Tasks accepted from Kafka |
| `tasks_duplicates_skipped_total` | Redeliveries discarded |
| `tasks_claimed_total` | Claimed by this instance's workers |
| `tasks_execution_seconds` | Execution duration, with percentiles |
| `tasks_failed_total` | Finished with an error |
| `tasks_recovered_total` | Stuck tasks returned to the queue |
| `tasks_retries_exhausted_total` | Given up on after all retries |
| `tasks_queue_depth` | Tasks waiting to be executed |
| `worker_pool_busy` / `worker_pool_size` | Pool occupancy |

Counters are local to an instance; `tasks_queue_depth` is read from the shared
database at scrape time. Metrics from different instances are distinguished by
the `application` tag.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `app.worker.pool-size` | 8 | Number of workers |
| `app.worker.poll-interval-ms` | 1000 | Queue polling interval |
| `app.worker.progress-update-interval-ms` | 500 | How often progress is saved |
| `app.worker.enabled` | true | Whether this instance executes tasks |
| `app.recovery.enabled` | true | Whether stuck-task recovery runs |
| `app.recovery.stuck-timeout-ms` | 900000 | Stuck threshold; strictly above 600000 |
| `app.recovery.interval-ms` | 30000 | How often stuck tasks are checked |
| `app.recovery.max-retries` | 3 | Requeues before giving up |
| `app.recovery.batch-size` | 50 | Batch size per pass |
| `app.kafka.topic` | tasks | Topic carrying tasks |
| `app.kafka.dlt-topic` | tasks.DLT | Topic for unrecoverable messages |
| `app.kafka.partitions` | 3 | At least as many partitions as instances |
| `spring.datasource.hikari.maximum-pool-size` | 12 | Database connections |

The connection pool is kept larger than the worker count: connections are needed
by the workers (for short status updates), by the scheduler and by the REST layer.

## Development

Infrastructure in containers, application on the host:

```bash
docker compose up -d postgres kafka kafka-ui
./mvnw spring-boot:run
```

The application reaches Kafka at `localhost:29092`. The broker exposes two client
listeners: `kafka:9092` for containers and `localhost:29092` for host processes —
without the second one an application running outside Docker would receive the
unresolvable hostname `kafka` from the broker metadata.

### Migrations

Liquibase, with XML changelogs under `src/main/resources/db/changelog`. Hibernate
runs with `ddl-auto: validate` and never modifies the schema. CHECK constraints
and partial indexes live in `.sql` files included via `<sqlFile>`: Liquibase tags
cannot express them.

### Tests

```bash
./mvnw test
```

Docker must be running: the integration tests start their own PostgreSQL and
Kafka through Testcontainers, separate from `docker compose`. H2 would not work
here — `SKIP LOCKED`, partial indexes and CHECK constraints do not reproduce on it.

Covered: concurrent task claiming, Kafka message intake and validation,
deduplication of redeliveries, routing of unrecoverable messages to the DLT,
asynchronous execution with intermediate progress, pool parallelism, stuck-task
recovery, the REST contract and its error codes.

## Out of scope

Not implemented from the production-readiness section of the assignment:
authentication and authorization (Spring Security), Redis caching, CI/CD
configuration.
