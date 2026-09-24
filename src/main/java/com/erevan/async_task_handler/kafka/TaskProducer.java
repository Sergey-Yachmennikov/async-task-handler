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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
     * Отправляет задачу и дожидается подтверждения от брокера.
     * <p>
     * {@code send()} у {@code KafkaTemplate} асинхронный: не дождись мы его
     * результата, клиент получил бы 202 даже если брокер так и не подтвердил
     * запись — задача осталась бы только в памяти этого инстанса.
     *
     * @return ключ сообщения — сквозной идентификатор для сопоставления с логами
     * @throws TaskPublishException если брокер не подтвердил запись за отведённое время
     */
    public String send(TaskRequestDto request) {
        String key = UUID.randomUUID().toString();
        try {
            kafkaTemplate.send(properties.kafka().topic(), key, request)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskPublishException("Отправка в Kafka прервана", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new TaskPublishException("Kafka не подтвердила запись сообщения", e);
        }

        // Логируется только после подтверждения — до этого момента задача
        // в топике могла и не оказаться
        log.info("Задача отправлена в топик {}: key={}, name={}",
                properties.kafka().topic(), key, request.name());
        return key;
    }
}
