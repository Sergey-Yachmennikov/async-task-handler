package com.erevan.async_task_handler.exception;

/**
 * Kafka не подтвердила запись сообщения (брокер недоступен, таймаут).
 * Через GlobalExceptionHandler превращается в 503: клиент получил бы 202
 * без гарантии, что задача вообще куда-то попала.
 */
public class TaskPublishException extends RuntimeException {

    public TaskPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
