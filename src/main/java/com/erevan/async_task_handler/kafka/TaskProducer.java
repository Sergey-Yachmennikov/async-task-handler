package com.erevan.async_task_handler.kafka;

import com.erevan.async_task_handler.config.AppProperties;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.exception.TaskPublishException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Публикация задачи в топик.
 * <p>
 * Используется REST-эндпоинтом постановки: контроллер не пишет в БД сам,
 * а кладёт сообщение в Kafka и сразу отвечает. Регистрация происходит потом,
 * в общем для всех источников консьюмере, так что путь у задачи ровно один
 * независимо от того, пришла она из REST или от внешнего продюсера.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskProducer {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AppProperties properties;

    /**
     * Отправляет задачу и дожидается подтверждения от брокера — не блокируя
     * вызывающий поток: результат приходит через возвращённую future.
     * <p>
     * {@code send()} у {@code KafkaTemplate} сам по себе асинхронный: не дождись
     * мы его результата, клиент получил бы 202 даже если брокер так и не
     * подтвердил запись — задача осталась бы только в памяти этого инстанса.
     * Ждём результат здесь же, в цепочке {@code CompletableFuture}, а не через
     * блокирующий {@code get()}: вызывающий (контроллер) тоже асинхронный
     * и не держит поток сервлета на время ожидания брокера.
     *
     * @return future с ключом сообщения — сквозным идентификатором для
     *         сопоставления с логами; при неудаче завершается исключительно
     *         с {@link TaskPublishException} (таймаут или отказ брокера)
     */
    public CompletableFuture<String> send(TaskRequestDto request) {
        String key = UUID.randomUUID().toString();
        return kafkaTemplate.send(properties.kafka().topic(), key, request)
                .orTimeout(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .handle((result, ex) -> {

                    if (ex != null) {
                        throw new TaskPublishException("Kafka не подтвердила запись сообщения", ex);
                    }

                    log.info("Задача отправлена в топик {}: key={}, name={}",
                            properties.kafka().topic(), key, request.name());
                    return key;
                });
    }
}
