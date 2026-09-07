package com.erevan.async_task_handler.kafka;

import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.exception.InvalidTaskMessageException;
import com.erevan.async_task_handler.service.TaskRegistrationService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Приём задач из Kafka, п.1 ТЗ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskConsumer {

    private final TaskRegistrationService registrationService;
    private final Validator validator;

    @KafkaListener(
            topics = "${app.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, TaskRequestDto> message) {
        TaskRequestDto request = message.value();

        log.debug("Получено сообщение: partition={}, offset={}, key={}",
                message.partition(), message.offset(), message.key());

        /*
         * Валидация выполняется здесь вручную, а не через @Valid на параметре.
         * @Valid дал бы исключение типа, который обработчик ошибок считает
         * пригодным для повтора, и сообщение бесполезно кружило бы по попыткам.
         * InvalidTaskMessageException объявлено неустранимым, поэтому такое
         * сообщение уходит в DLT сразу.
         */
        Set<ConstraintViolation<TaskRequestDto>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new InvalidTaskMessageException(
                    "Сообщение не прошло валидацию: " + formatViolations(violations));
        }

        // Ошибки на этом вызове (например, недоступная БД) наоборот устранимы
        // со временем, поэтому обработчик ошибок их повторит
        registrationService.register(request, message.key());
    }

    private String formatViolations(Set<ConstraintViolation<TaskRequestDto>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
    }
}
