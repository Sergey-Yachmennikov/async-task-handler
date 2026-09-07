package com.erevan.async_task_handler.mapper;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.dto.TaskResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Реализацию маппера генерирует MapStruct, поэтому она нуждается в проверке
 * не меньше рукописной: опечатка в аннотации или незамеченный обработчик
 * дают компилируемый, но неверный код — например, маппер, который молча
 * ничего не заполняет.
 * <p>
 * Контекст Spring не поднимается: сгенерированный класс — обычный Java-объект.
 */
class TaskMapperTest {

    private final TaskMapper mapper = new TaskMapperImpl();

    @Test
    @DisplayName("toEntity переносит поля запроса и ставит статус NEW")
    void toEntityCopiesRequestAndSetsInitialStatus() {
        Task task = mapper.toEntity(new TaskRequestDto("generate-report", 5_000L));

        assertThat(task.getName()).isEqualTo("generate-report");
        assertThat(task.getDurationMs()).isEqualTo(5_000L);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    @DisplayName("toEntity не заполняет поля, за которые отвечают БД и воркер")
    void toEntityLeavesLifecycleFieldsUntouched() {
        Task task = mapper.toEntity(new TaskRequestDto("task", 1_000L));

        assertThat(task.getId()).isNull();
        assertThat(task.getProgress()).isZero();
        assertThat(task.getResult()).isNull();
        assertThat(task.getErrorMessage()).isNull();
        assertThat(task.getWorkerId()).isNull();
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.getFinishedAt()).isNull();
        assertThat(task.getRetryCount()).isZero();
        // Ключ дедупликации берётся из метаданных сообщения,
        // а не из его тела — его проставляет TaskRegistrationService
        assertThat(task.getDedupKey()).isNull();
    }

    @Test
    @DisplayName("toResponse переносит все поля, видимые через REST")
    void toResponseCopiesAllExposedFields() {
        Instant created = Instant.parse("2026-09-07T10:00:00Z");
        Instant started = Instant.parse("2026-09-07T10:00:01Z");
        Instant finished = Instant.parse("2026-09-07T10:00:06Z");

        Task task = new Task("report", 5_000L);
        task.setId(42L);
        task.setStatus(TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setResult("Задача выполнена за 5000 мс");
        task.setErrorMessage(null);
        task.setWorkerId("node-1");
        task.setCreatedAt(created);
        task.setStartedAt(started);
        task.setFinishedAt(finished);
        // Внутренние поля наружу попасть не должны
        task.setRetryCount(2);
        task.setDedupKey("kafka-key-1");

        TaskResponseDto response = mapper.toResponse(task);

        assertThat(response).isEqualTo(new TaskResponseDto(
                42L, "report", 5_000L, TaskStatus.COMPLETED, 100,
                "Задача выполнена за 5000 мс", null, "node-1",
                created, started, finished));
    }

    @Test
    @DisplayName("null на входе даёт null на выходе")
    void nullInputsAreTolerated() {
        assertThat(mapper.toEntity(null)).isNull();
        assertThat(mapper.toResponse(null)).isNull();
    }
}
