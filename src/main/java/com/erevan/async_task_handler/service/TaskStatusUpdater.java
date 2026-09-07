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
 * <p>
 * Каждая запись проверяет, что задача всё ещё принадлежит текущей попытке.
 * Без такой проверки возможен следующий сценарий: воркер подвис — долгая
 * пауза сборщика мусора, приостановленная виртуалка, — восстановление сочло
 * задачу зависшей и вернуло в очередь, её захватил другой инстанс и начал
 * выполнять. Очнувшийся воркер записал бы свой результат поверх чужой
 * выполняющейся попытки. Проверка делает корректность независимой от того,
 * насколько верно настроен порог зависания.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStatusUpdater {

    private final TaskRepository taskRepository;
    private final WorkerIdentity workerIdentity;

    /** Промежуточный результат: доля выполнения, видна через REST по ходу работы. */
    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = 3, delay = 100, multiplier = 2)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(Long taskId, int progress) {
        Task task = findOrThrow(taskId);
        if (notOwnedByCurrentAttempt(task, "сохранение прогресса")) {
            return;
        }
        task.setProgress(progress);
    }

    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = 3, delay = 100, multiplier = 2)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(Long taskId, String result) {
        Task task = findOrThrow(taskId);
        if (notOwnedByCurrentAttempt(task, "запись успешного завершения")) {
            return;
        }
        task.setStatus(TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setResult(result);
        task.setFinishedAt(Instant.now());
    }

    @Retryable(includes = OptimisticLockingFailureException.class, maxRetries = 3, delay = 100, multiplier = 2)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId, String errorMessage) {
        Task task = findOrThrow(taskId);
        if (notOwnedByCurrentAttempt(task, "запись ошибки")) {
            return;
        }
        task.setStatus(TaskStatus.FAILED);
        task.setErrorMessage(errorMessage);
        task.setFinishedAt(Instant.now());
    }

    /**
     * Задача считается своей, только если она всё ещё выполняется и числится
     * за этим инстансом.
     * <p>
     * Гонку между этой проверкой и коммитом закрывает {@code @Version}: успей
     * кто-то изменить строку следом, коммит упадёт с конфликтом версий,
     * {@code @Retryable} перечитает задачу и увидит уже новое состояние.
     */
    private boolean notOwnedByCurrentAttempt(Task task, String action) {
        boolean owned = task.getStatus() == TaskStatus.IN_PROGRESS
                && workerIdentity.id().equals(task.getWorkerId());
        if (!owned) {
            log.warn("Задача {} больше не принадлежит этой попытке (статус {}, владелец {}) — "
                            + "{} пропущено",
                    task.getId(), task.getStatus(), task.getWorkerId(), action);
        }
        return !owned;
    }

    private Task findOrThrow(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }
}
