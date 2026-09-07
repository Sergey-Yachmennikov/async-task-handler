package com.erevan.async_task_handler.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки сервиса из секции {@code app} в application.yaml.
 * <p>
 * Типобезопасная привязка вместо россыпи {@code @Value}: неверное значение
 * ловится на старте приложения, а не в момент первого обращения к полю.
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(Kafka kafka, Worker worker, Recovery recovery) {

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

            /*
             * Партиций должно быть не меньше, чем инстансов сервиса: в одной
             * consumer group партиция достаётся ровно одному консьюмеру,
             * и лишние инстансы просто простаивали бы без работы.
             */
            @Min(1)
            int partitions
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
            long progressUpdateIntervalMs
    ) {
    }

    /** Возврат в строй задач, зависших после падения инстанса. */
    public record Recovery(

            boolean enabled,

            /**
             * Сколько задача может находиться в IN_PROGRESS, прежде чем будет
             * признана зависшей.
             * <p>
             * Значение обязано превышать максимально допустимую длительность
             * задачи. Иначе восстановление отберёт задачу у живого воркера,
             * который её честно выполняет, и она будет выполнена дважды.
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
