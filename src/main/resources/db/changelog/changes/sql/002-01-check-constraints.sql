-- Дублируем на уровне БД то, что валидируется в DTO: инстансов сервиса много,
-- а база одна, и она остаётся последней линией защиты от некорректных данных.

ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_duration_positive CHECK (duration_ms > 0);

ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_progress_range CHECK (progress BETWEEN 0 AND 100);

ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_status
        CHECK (status IN ('NEW', 'IN_PROGRESS', 'COMPLETED', 'FAILED'));
