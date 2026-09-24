package com.erevan.async_task_handler.domain;

/** Границы, общие для нескольких частей системы. */
public final class TaskConstraints {

    /**
     * Максимальная длительность одной задачи, миллисекунды.
     * <p>
     * Ограничение нужно, чтобы одно сообщение не заняло воркер на неограниченное
     * время: пул конечен, и такая задача выключила бы из работы целый поток.
     */
    public static final long MAX_DURATION_MS = 600_000L;

    private TaskConstraints() {
    }
}
