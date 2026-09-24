package com.erevan.async_task_handler.controller;

import com.erevan.async_task_handler.dto.TaskAcceptedDto;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.dto.TaskResponseDto;
import com.erevan.async_task_handler.kafka.TaskProducer;
import com.erevan.async_task_handler.service.TaskQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link TaskApi}: привязка к HTTP и вызовы сервисов.
 * Описание для документации и ограничения валидации — в интерфейсе.
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController implements TaskApi {

    private final TaskQueryService taskQueryService;
    private final TaskProducer taskProducer;

    @Override
    @GetMapping("/{id}")
    public TaskResponseDto getTask(@PathVariable Long id) {
        return taskQueryService.findById(id);
    }

    @Override
    @GetMapping
    public TaskResponseDto getTaskByCorrelationKey(@RequestParam String correlationKey) {
        return taskQueryService.findByCorrelationKey(correlationKey);
    }

    @Override
    @PostMapping
    public CompletableFuture<ResponseEntity<TaskAcceptedDto>> submitTask(@RequestBody TaskRequestDto request) {
        return taskProducer.send(request)
                .thenApply(correlationKey -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new TaskAcceptedDto("Задача принята в обработку", correlationKey)));
    }
}
