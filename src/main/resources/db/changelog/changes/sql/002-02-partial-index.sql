-- Индекс под горячий запрос захвата задачи воркером:
--
--   SELECT * FROM tasks WHERE status = 'NEW'
--   ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
--
-- Индекс частичный (WHERE status = 'NEW'): в него попадают только строки
-- необработанной очереди, а не вся история завершённых задач. Поэтому его
-- размер держится на уровне реальной глубины очереди и не растёт со временем.
--
-- CONCURRENTLY здесь неприменимо: Liquibase выполняет changeset в транзакции.
-- На пустой таблице при первичном разворачивании это и не нужно.

CREATE INDEX idx_tasks_new_queue ON tasks (id) WHERE status = 'NEW';
