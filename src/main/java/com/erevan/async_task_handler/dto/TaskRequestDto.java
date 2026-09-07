package com.erevan.async_task_handler.dto;

import com.erevan.async_task_handler.domain.TaskConstraints;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Входящая задача: приходит сообщением из Kafka либо телом POST /api/tasks.
 * <p>
 * Записью (record), а не классом с @Data: DTO неизменяем, а Jackson
 * и Bean Validation работают с records напрямую.
 */
@Schema(description = "Запрос на постановку задачи в очередь")
public record TaskRequestDto(

        @Schema(description = "Название задачи", example = "generate-report")
        @NotBlank(message = "Название задачи обязательно")
        @Size(max = 255, message = "Название не длиннее {max} символов")
        String name,

        // Верхняя граница общая с настройкой восстановления зависших задач,
        // поэтому вынесена в константу — см. TaskConstraints
        @Schema(description = "Длительность выполнения в миллисекундах", example = "5000")
        @NotNull(message = "Длительность обязательна")
        @Positive(message = "Длительность должна быть положительной")
        @Max(value = TaskConstraints.MAX_DURATION_MS, message = "Длительность не больше {value} мс")
        Long durationMs
) {
}
