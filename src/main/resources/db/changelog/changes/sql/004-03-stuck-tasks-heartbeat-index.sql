-- Заменяет idx_tasks_stuck (был по started_at): обнаружение зависших задач
-- теперь смотрит на heartbeat_at, который воркер обновляет вместе с каждым
-- сохранением прогресса, а не на момент захвата. Порог зависания больше
-- не обязан превышать максимально допустимую длительность задачи.

DROP INDEX idx_tasks_stuck;

CREATE INDEX idx_tasks_stuck_heartbeat ON tasks (heartbeat_at) WHERE status = 'IN_PROGRESS';
