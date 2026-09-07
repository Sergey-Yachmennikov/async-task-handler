package com.erevan.async_task_handler.dto;

import com.erevan.async_task_handler.domain.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Состояние задачи, отдаваемое по GET /api/tasks/{id}.
 * <p>
 * Отдаётся именно DTO, а не сущность: иначе наружу утекли бы служебные поля
 * (version) и любое изменение схемы БД ломало бы контракт API.
 */
@Schema(description = "Текущее состояние и результат выполнения задачи")
public record TaskResponseDto(

        @Schema(description = "Идентификатор задачи", example = "42")
        Long id,

        @Schema(description = "Название задачи", example = "generate-report")
        String name,

        @Schema(description = "Заказанная длительность выполнения, мс", example = "5000")
        Long durationMs,

        @Schema(description = "Статус выполнения", example = "IN_PROGRESS")
        TaskStatus status,

        @Schema(description = "Промежуточный результат: доля выполнения, 0..100", example = "40")
        int progress,

        @Schema(description = "Итог успешного выполнения")
        String result,

        @Schema(description = "Причина сбоя, заполняется только для FAILED")
        String errorMessage,

        @Schema(description = "Инстанс сервиса, выполнивший задачу", example = "ath-7c9f4b2a")
        String workerId,

        @Schema(description = "Момент постановки в очередь")
        Instant createdAt,

        @Schema(description = "Момент захвата воркером")
        Instant startedAt,

        @Schema(description = "Момент завершения — успешного или нет")
        Instant finishedAt
) {
}
