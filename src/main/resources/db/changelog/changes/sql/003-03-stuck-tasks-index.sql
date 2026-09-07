-- Индекс под запрос восстановления зависших задач:
--
--   SELECT * FROM tasks
--   WHERE status = 'IN_PROGRESS' AND started_at < :threshold
--   ORDER BY started_at LIMIT ? FOR UPDATE SKIP LOCKED
--
-- Как и очередь NEW, индекс частичный: выполняющихся задач в каждый момент
-- не больше суммарной ёмкости пулов всех инстансов, то есть десятки строк.
-- Полный индекс по started_at рос бы вместе со всей историей задач,
-- принося пользу только этой доле.

CREATE INDEX idx_tasks_stuck ON tasks (started_at) WHERE status = 'IN_PROGRESS';
