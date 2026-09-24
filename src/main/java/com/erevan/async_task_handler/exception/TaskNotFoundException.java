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

    /**
     * Задача ещё не зарегистрирована по этому ключу — либо потому, что
     * консьюмер её пока не обработал, либо ключ ошибочный.
     */
    public TaskNotFoundException(String correlationKey) {
        super("Задача с correlationKey '" + correlationKey + "' не найдена: "
                + "либо ключ неверный, либо задача ещё не дошла до БД");
        this.taskId = null;
    }

    public Long getTaskId() {
        return taskId;
    }
}
