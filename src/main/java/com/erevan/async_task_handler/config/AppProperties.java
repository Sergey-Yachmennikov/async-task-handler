package com.erevan.async_task_handler.config;

import com.erevan.async_task_handler.domain.TaskConstraints;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
/*
 * @Valid на каждой вложенной записи обязателен и это не формальность.
 * @Validated включает проверку самого объекта, но Bean Validation не спускается
 * во вложенные объекты без явного указания каскада. Без этих аннотаций все
 * ограничения ниже — @Min, @NotBlank, @AssertTrue — остаются просто разметкой:
 * никакой ошибки при этом не выводится, конфигурация молча принимает
 * любые значения.
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

        /**
         * Порог зависания обязан превышать предельную длительность задачи.
         * <p>
         * Проверка межполевая, и она закрывает единственный способ незаметно
         * испортить данные конфигурацией. Поставь порог меньше — и восстановление
         * отберёт задачу у живого воркера, который её честно выполняет: она
         * вернётся в очередь, её захватит другой инстанс, и задача выполнится
         * дважды. Ни исключения, ни отказа при этом не будет.
         * <p>
         * Раньше это требование жило только в комментарии. Теперь неверное
         * сочетание значений роняет приложение на старте.
         */
        @AssertTrue(message = "app.recovery.stuck-timeout-ms должен превышать "
                + "максимальную длительность задачи (" + TaskConstraints.MAX_DURATION_MS + " мс)")
        public boolean isStuckTimeoutAboveMaxTaskDuration() {
            return stuckTimeoutMs > TaskConstraints.MAX_DURATION_MS;
        }
    }
}
