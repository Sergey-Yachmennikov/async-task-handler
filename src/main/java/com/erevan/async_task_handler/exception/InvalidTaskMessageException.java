package com.erevan.async_task_handler.exception;

/**
 * Сообщение из Kafka не прошло валидацию.
 * <p>
 * Помечено как неустранимое: повторная доставка того же тела ничего не изменит,
 * поэтому обработчик ошибок отправляет такое сообщение сразу в DLT,
 * не тратя попытки и не задерживая остальную очередь.
 */
public class InvalidTaskMessageException extends RuntimeException {

    public InvalidTaskMessageException(String message) {
        super(message);
    }
}
