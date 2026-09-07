package com.erevan.async_task_handler.kafka;

import com.erevan.async_task_handler.config.AppProperties;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

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

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AppProperties properties;

    /**
     * @return ключ сообщения — сквозной идентификатор для сопоставления с логами
     */
    public String send(TaskRequestDto request) {
        String key = UUID.randomUUID().toString();
        kafkaTemplate.send(properties.kafka().topic(), key, request);
        log.info("Задача отправлена в топик {}: key={}, name={}",
                properties.kafka().topic(), key, request.name());
        return key;
    }
}
