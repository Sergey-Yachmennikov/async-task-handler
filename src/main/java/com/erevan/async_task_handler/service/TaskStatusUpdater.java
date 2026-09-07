package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.exception.TaskNotFoundException;
import com.erevan.async_task_handler.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Сохранение промежуточных и финальных результатов, п.4 ТЗ.
 * <p>
 * Вынесено отдельным бином не ради красоты: методы вызываются из воркера,
 * работающего вне транзакции, и обращение к ним изнутри того же класса
 * прошло бы мимо прокси Spring — аннотации {@code @Transactional}
 * и {@code @Retryable} просто не сработали бы.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStatusUpdater {

    private final TaskRepository taskRepository;

    /** Промежуточный результат: доля выполнения, видна через REST по ходу работы. */
    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = 3, delay = 100, multiplier = 2)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(Long taskId, int progress) {
        Task task = findOrThrow(taskId);
        task.setProgress(progress);
    }

    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = 3, delay = 100, multiplier = 2)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(Long taskId, String result) {
        Task task = findOrThrow(taskId);
        task.setStatus(TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setResult(result);
        task.setFinishedAt(Instant.now());
        log.info("Задача {} завершена успешно", taskId);
    }

    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = 3, delay = 100, multiplier = 2)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId, String errorMessage) {
        Task task = findOrThrow(taskId);
        task.setStatus(TaskStatus.FAILED);
        task.setErrorMessage(errorMessage);
        task.setFinishedAt(Instant.now());
        log.warn("Задача {} завершена с ошибкой: {}", taskId, errorMessage);
    }

    private Task findOrThrow(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }
}
