package com.erevan.async_task_handler.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки сервиса из секции {@code app} в application.yaml.
 * <p>
 * Типобезопасная привязка вместо россыпи {@code @Value}: неверное значение
 * ловится на старте приложения, а не в момент первого обращения к полю.
 * <p>
 * {@code @Valid} на каждой вложенной записи обязателен: {@code @Validated}
 * включает проверку самого объекта, но Bean Validation не спускается во
 * вложенные объекты без явного указания каскада. Без него {@code @Min},
 * {@code @NotBlank} и т.п. ниже остаются просто разметкой.
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(@Valid Kafka kafka, @Valid Worker worker, @Valid Recovery recovery) {

    public record Kafka(

            @NotBlank
            String topic,

            /**
             * Топик для сообщений, которые не удалось обработать.
             * Должен иметь не меньше партиций, чем основной: получатель
             * по умолчанию сохраняет номер партиции исходного сообщения.
             */
            @NotBlank
            String dltTopic,

            /** Партиций не меньше, чем инстансов сервиса: лишние инстансы простаивали бы без работы. */
            @Min(1)
            int partitions,

            /** Пауза перед повторной обработкой сообщения после временного сбоя (например, недоступной БД), мс. */
            @Min(0)
            long retryIntervalMs,

            /** Сколько раз повторить обработку сообщения, прежде чем отправить его в DLT. */
            @Min(0)
            long retryMaxAttempts
    ) {
    }

    public record Worker(

            /**
             * Позволяет поднять инстанс только как приёмник задач из Kafka
             * и REST-фасад, не участвующий в их выполнении. Используется
             * в тестах приёма, где выполнение задач мешало бы проверке.
             */
            boolean enabled,

            /** Количество воркеров, п.3 ТЗ. */
            @Min(1)
            int poolSize,

            /** Период опроса очереди задач планировщиком, мс. */
            @Min(1)
            long pollIntervalMs,

            /** Как часто воркер сохраняет промежуточный прогресс, мс. */
            @Min(1)
            long progressUpdateIntervalMs,

            /** Сколько ждать доработки текущих задач при остановке сервиса, прежде чем прервать их принудительно, мс. */
            @Min(1)
            long shutdownTimeoutMs
    ) {
    }

    /** Возврат в строй задач, зависших после падения инстанса. */
    public record Recovery(

            boolean enabled,

            /**
             * Сколько задача может провести без обновления {@code heartbeat_at},
             * прежде чем будет признана зависшей. Воркер обновляет отметку вместе
             * с каждым сохранением прогресса, поэтому порог не завязан на
             * максимально допустимую длительность задачи и может быть коротким.
             */
            @Min(1)
            long stuckTimeoutMs,

            /** Период проверки, мс. Заметно реже опроса очереди: событие редкое. */
            @Min(1)
            long intervalMs,

            /** Сколько раз возвращать задачу в очередь, прежде чем признать провалившейся. */
            @Min(0)
            int maxRetries,

            /** Ограничение на размер пачки за один проход. */
            @Min(1)
            int batchSize
    ) {
    }
}
