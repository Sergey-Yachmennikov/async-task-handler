package com.erevan.async_task_handler.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Подтверждение приёма задачи в обработку.
 * <p>
 * Идентификатора задачи здесь нет намеренно: на этот момент сообщение только
 * положено в Kafka, а запись в БД со своим id создаст консьюмер. Возвращать
 * id, которого ещё не существует, значило бы обещать больше, чем сделано.
 */
@Schema(description = "Задача принята в обработку")
public record TaskAcceptedDto(

        @Schema(description = "Пояснение", example = "Задача принята в обработку")
        String message,

        @Schema(description = "Ключ сообщения в Kafka — для сопоставления с логами",
                example = "3f2b9c1e-4a7d-4c3e-9f10-2b8e5d7a1c04")
        String correlationKey
) {
}
