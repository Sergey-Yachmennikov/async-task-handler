package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.config.AppProperties;
import com.erevan.async_task_handler.metrics.TaskMetrics;
import com.erevan.async_task_handler.service.TaskClaimService.ClaimedTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Выполнение задачи воркером, п.3 ТЗ.
 * <p>
 * Работает вне транзакции: соединение с БД берётся только на короткие
 * обновления статуса, а не удерживается на всё время выполнения.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRunner {

    private final TaskStatusUpdater statusUpdater;
    private final AppProperties properties;
    private final TaskMetrics taskMetrics;

    public void run(ClaimedTask task) {
        log.debug("Начато выполнение задачи {} ({} мс)", task.id(), task.durationMs());
        // Замеряется фактическое время работы воркера, а не заказанная
        // длительность: в него входят и паузы на сохранение прогресса
        long startedAt = System.nanoTime();
        try {
            simulateWork(task);
            statusUpdater.markCompleted(task.id(),
                    "Задача выполнена за " + task.durationMs() + " мс");
            taskMetrics.recordCompleted(Duration.ofNanos(System.nanoTime() - startedAt));
        } catch (InterruptedException e) {
            // Прилетает при остановке сервиса. Флаг прерывания обязательно
            // восстанавливаем: иначе пул не поймёт, что его останавливают
            Thread.currentThread().interrupt();
            markFailedSafely(task.id(), "Выполнение прервано остановкой сервиса");
        } catch (Exception e) {
            log.error("Ошибка выполнения задачи {}", task.id(), e);
            markFailedSafely(task.id(), "Ошибка: " + e.getMessage());
        }
    }

    /**
     * Записывает провал задачи, не позволяя исключению уйти дальше.
     * <p>
     * Это последнее действие обработчика ошибки, и сорваться оно вполне может:
     * недоступна БД, задача удалена извне. Если такое исключение выпустить,
     * оно уйдёт в поток пула, где его никто не перехватывает — исходная
     * причина сбоя потеряется, а в логах не останется ничего.
     */
    private void markFailedSafely(Long taskId, String message) {
        try {
            statusUpdater.markFailed(taskId, message);
            taskMetrics.recordFailed();
        } catch (Exception e) {
            log.error("Не удалось сохранить статус FAILED для задачи {}: {}", taskId, message, e);
        }
    }

    /**
     * Имитация полезной работы через сон.
     * <p>
     * Сон разбит на отрезки, и между ними сохраняется прогресс — это и есть
     * промежуточные результаты из п.4 ТЗ. Один сплошной {@code sleep(duration)}
     * оставил бы задачу непрозрачной: до самого конца по REST было бы видно
     * только IN_PROGRESS без признаков движения.
     */
    private void simulateWork(ClaimedTask task) throws InterruptedException {
        long total = task.durationMs();
        long step = properties.worker().progressUpdateIntervalMs();
        long elapsed = 0;

        while (elapsed < total) {
            long chunk = Math.min(step, total - elapsed);
            Thread.sleep(chunk);
            elapsed += chunk;

            // Последний отрезок не пишем: прогресс 100 выставится вместе
            // со статусом COMPLETED одной транзакцией
            if (elapsed < total) {
                statusUpdater.updateProgress(task.id(), (int) (elapsed * 100 / total));
            }
        }
    }
}
