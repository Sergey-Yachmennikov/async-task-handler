package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.config.AppProperties;
import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.metrics.TaskMetrics;
import com.erevan.async_task_handler.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Возврат в строй задач, зависших в статусе IN_PROGRESS.
 * <p>
 * Проблема, которую это решает: инстанс захватил задачу, перевёл её
 * в IN_PROGRESS и упал, не успев дописать финальный статус. Выполнять задачу
 * уже некому, но и в очередь она не вернётся — планировщик отбирает только
 * задачи в статусе NEW. Без такой проверки строка осталась бы в IN_PROGRESS
 * навсегда.
 * <p>
 * Признак зависания — слишком давний {@code heartbeat_at}, а не заказанная
 * длительность задачи: воркер обновляет его вместе с каждым сохранением
 * прогресса, поэтому порог не нужно подгонять под максимально возможную
 * длительность и он может быть коротким.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StuckTaskRecoveryService {

    private final TaskRepository taskRepository;
    private final AppProperties properties;
    private final TaskMetrics taskMetrics;

    /**
     * Транзакция здесь длиннее, чем при обычном захвате, но безопасна:
     * внутри только чтение и обновление строк, без ожидания внешних систем.
     * SKIP LOCKED не даёт двум инстансам поднять одну и ту же задачу.
     */
    @Scheduled(fixedDelayString = "${app.recovery.interval-ms}")
    @Transactional
    public void recoverStuckTasks() {
        AppProperties.Recovery config = properties.recovery();
        Instant threshold = Instant.now().minusMillis(config.stuckTimeoutMs());

        List<Task> stuck = taskRepository.lockStuckTasks(threshold, config.batchSize());
        if (stuck.isEmpty()) {
            return;
        }

        log.warn("Обнаружено зависших задач: {}", stuck.size());
        for (Task task : stuck) {
            if (task.getRetryCount() < config.maxRetries()) {
                returnToQueue(task);
            } else {
                giveUp(task, config.maxRetries());
            }
        }
    }

    private void returnToQueue(Task task) {
        // requeueAfterStuckRecovery стирает и следы прошлой попытки (прогресс,
        // владельца, токен): иначе задача вернулась бы в очередь с чужим
        // прогрессом и признаком уже не работающего инстанса
        task.requeueAfterStuckRecovery();
        log.info("Задача {} возвращена в очередь, попытка {}", task.getId(), task.getRetryCount());
        taskMetrics.recordRecovered();
    }

    private void giveUp(Task task, int maxRetries) {
        task.fail("Задача зависла в IN_PROGRESS и исчерпала попытки восстановления (%d)"
                .formatted(maxRetries), Instant.now());
        log.error("Задача {} признана провалившейся после {} попыток", task.getId(), maxRetries);
        taskMetrics.recordExhausted();
    }
}
