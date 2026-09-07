package com.erevan.async_task_handler.metrics;

import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.repository.TaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Метрики обработки задач, доступные через /actuator/prometheus.
 * <p>
 * Счётчики создаются в конструкторе, а не инициализаторами полей. Разница
 * принципиальна: инициализаторы полей выполняются раньше, чем конструктор
 * присвоит внедрённый {@code MeterRegistry}, и попытка зарегистрировать
 * счётчик там упала бы с NullPointerException.
 */
@Component
public class TaskMetrics {

    private final Counter registered;
    private final Counter duplicatesSkipped;
    private final Counter claimed;
    private final Counter failed;
    private final Counter recovered;
    private final Counter exhausted;
    private final Timer executionTime;

    public TaskMetrics(MeterRegistry registry, TaskRepository taskRepository) {
        this.registered = Counter.builder("tasks.registered")
                .description("Задачи, принятые из Kafka и сохранённые в БД")
                .register(registry);
        this.duplicatesSkipped = Counter.builder("tasks.duplicates.skipped")
                .description("Повторные доставки, отброшенные дедупликацией")
                .register(registry);
        this.claimed = Counter.builder("tasks.claimed")
                .description("Задачи, захваченные воркерами этого инстанса")
                .register(registry);
        this.failed = Counter.builder("tasks.failed")
                .description("Задачи, завершившиеся ошибкой")
                .register(registry);
        this.recovered = Counter.builder("tasks.recovered")
                .description("Зависшие задачи, возвращённые в очередь")
                .register(registry);
        this.exhausted = Counter.builder("tasks.retries.exhausted")
                .description("Задачи, признанные провалившимися после исчерпания попыток")
                .register(registry);
        this.executionTime = Timer.builder("tasks.execution")
                .description("Длительность успешного выполнения задачи")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        /*
         * Глубина очереди читается из БД в момент опроса метрик, а не хранится
         * счётчиком в памяти. Счётчик показывал бы только то, что видел этот
         * инстанс, тогда как очередь общая на всех — и её реальный размер
         * знает лишь база.
         *
         * Запрос ложится на частичный индекс idx_tasks_new_queue, поэтому
         * обходится дёшево даже на большой таблице.
         */
        Gauge.builder("tasks.queue.depth", taskRepository,
                        repository -> repository.countByStatus(TaskStatus.NEW))
                .description("Задачи, ожидающие выполнения")
                .register(registry);
    }

    public void recordRegistered() {
        registered.increment();
    }

    public void recordDuplicateSkipped() {
        duplicatesSkipped.increment();
    }

    public void recordClaimed(int count) {
        claimed.increment(count);
    }

    public void recordCompleted(Duration duration) {
        executionTime.record(duration);
    }

    public void recordFailed() {
        failed.increment();
    }

    public void recordRecovered() {
        recovered.increment();
    }

    public void recordExhausted() {
        exhausted.increment();
    }
}
