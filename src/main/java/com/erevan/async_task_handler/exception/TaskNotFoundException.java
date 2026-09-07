package com.erevan.async_task_handler.exception;

/**
 * Задача с указанным идентификатором отсутствует в БД.
 * Через GlobalExceptionHandler превращается в 404.
 */
public class TaskNotFoundException extends RuntimeException {

    private final Long taskId;

    public TaskNotFoundException(Long taskId) {
        super("Задача с id " + taskId + " не найдена");
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }
}
