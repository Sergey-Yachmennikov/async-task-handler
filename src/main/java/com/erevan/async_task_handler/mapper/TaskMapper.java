package com.erevan.async_task_handler.mapper;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.dto.TaskResponseDto;
import org.springframework.stereotype.Component;

/**
 * Преобразования между сущностью и DTO.
 * <p>
 * Написан руками: полей немного, а MapStruct добавил бы кодогенерацию
 * и annotation processor ради двух методов.
 */
@Component
public class TaskMapper {

    /** Новая задача всегда рождается в статусе NEW — его выставляет конструктор. */
    public Task toEntity(TaskRequestDto request) {
        return new Task(request.name(), request.durationMs());
    }

    public TaskResponseDto toResponse(Task task) {
        return new TaskResponseDto(
                task.getId(),
                task.getName(),
                task.getDurationMs(),
                task.getStatus(),
                task.getProgress(),
                task.getResult(),
                task.getErrorMessage(),
                task.getWorkerId(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getFinishedAt());
    }
}
