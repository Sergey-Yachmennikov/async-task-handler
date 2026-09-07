package com.erevan.async_task_handler.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Единое тело ответа при ошибке — для всех кодов, которые отдаёт API.
 */
@Schema(description = "Описание ошибки")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(

        @Schema(description = "Момент возникновения ошибки")
        Instant timestamp,

        @Schema(description = "HTTP-статус", example = "404")
        int status,

        @Schema(description = "Краткое обозначение ошибки", example = "Not Found")
        String error,

        @Schema(description = "Человекочитаемое пояснение", example = "Задача с id 42 не найдена")
        String message,

        @Schema(description = "Путь запроса", example = "/api/tasks/42")
        String path,

        @Schema(description = "Детализация по полям — только для ошибок валидации")
        List<FieldViolation> violations
) {

    /** Нарушение ограничения на конкретном поле запроса. */
    @Schema(description = "Ошибка валидации одного поля")
    public record FieldViolation(

            @Schema(description = "Имя поля", example = "durationMs")
            String field,

            @Schema(description = "Что именно нарушено", example = "Длительность должна быть положительной")
            String message
    ) {
    }

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse of(int status, String error, String message, String path,
                                   List<FieldViolation> violations) {
        return new ErrorResponse(Instant.now(), status, error, message, path, violations);
    }
}
