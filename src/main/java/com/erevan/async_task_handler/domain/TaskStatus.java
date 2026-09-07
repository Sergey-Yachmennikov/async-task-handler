package com.erevan.async_task_handler.domain;

/**
 * Жизненный цикл задачи.
 * <p>
 * Допустимые переходы: NEW → IN_PROGRESS → COMPLETED | FAILED.
 * Значения хранятся в БД строкой и продублированы CHECK-ограничением
 * chk_tasks_status, поэтому переименование константы требует миграции.
 */
public enum TaskStatus {

    /** Задача принята из Kafka и ждёт свободного воркера. */
    NEW,

    /** Задачу захватил воркер, выполнение идёт. */
    IN_PROGRESS,

    /** Выполнение завершилось успешно. */
    COMPLETED,

    /** Выполнение прервано ошибкой, причина в error_message. */
    FAILED
}
