package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.config.AppProperties;
import com.erevan.async_task_handler.metrics.TaskMetrics;
import com.erevan.async_task_handler.service.TaskClaimService.ClaimedTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

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
    private final TaskClaimService claimService;
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
                    "Задача выполнена за " + task.durationMs() + " мс", task.claimToken());
            // Логируется уже после фиксации транзакции: markCompleted вызывается
            // через прокси, и её транзакция закрывается вместе с возвратом
            // из метода. Запись изнутри транзакции соврала бы при неудачном коммите
            log.info("Задача {} завершена успешно", task.id());
            taskMetrics.recordCompleted(Duration.ofNanos(System.nanoTime() - startedAt));
        } catch (InterruptedException e) {
            // Прилетает при остановке сервиса — это не провал задачи, а внешняя
            // причина. Флаг прерывания обязательно восстанавливаем: иначе пул
            // не поймёт, что его останавливают. Саму задачу возвращаем в очередь,
            // чтобы её доделал другой инстанс, а не теряем как FAILED
            Thread.currentThread().interrupt();
            log.warn("Задача {} прервана остановкой сервиса, возвращаем в очередь", task.id());
            claimService.releaseClaim(List.of(task));
        } catch (Exception e) {
            log.error("Ошибка выполнения задачи {}", task.id(), e);
            markFailedSafely(task, "Ошибка: " + e.getMessage());
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
    private void markFailedSafely(ClaimedTask task, String message) {
        try {
            statusUpdater.markFailed(task.id(), message, task.claimToken());
            taskMetrics.recordFailed();
        } catch (Exception e) {
            log.error("Не удалось сохранить статус FAILED для задачи {}: {}", task.id(), message, e);
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
                saveProgressSafely(task, (int) (elapsed * 100 / total));
            }
        }
    }

    /**
     * Сбой при записи промежуточного прогресса не должен ронять задачу.
     * <p>
     * Это всего лишь наблюдаемость: секундный сбой БД не значит, что сама
     * работа не удалась. Падать задача должна из-за своей логики или финальной
     * записи, а не из-за временной недоступности соединения на полпути.
     */
    private void saveProgressSafely(ClaimedTask task, int progress) {
        try {
            statusUpdater.updateProgress(task.id(), progress, task.claimToken());
        } catch (Exception e) {
            log.warn("Не удалось сохранить прогресс {}% для задачи {}, продолжаем выполнение",
                    progress, task.id(), e);
        }
    }
}
