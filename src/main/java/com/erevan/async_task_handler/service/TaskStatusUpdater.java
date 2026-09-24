package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Сохранение промежуточных и финальных результатов, п.4 ТЗ.
 * <p>
 * Каждое обновление — одно условное {@code UPDATE ... WHERE status = 'IN_PROGRESS'
 * AND claim_token = :claimToken}, а не чтение сущности с последующим сохранением.
 * Условие в WHERE само по себе атомарно и заменяет проверку владения: гонки нет,
 * а раз нет гонки — не нужны ни {@code @Version}, ни ретраи на конфликте версий.
 * <p>
 * {@code claim_token} — это токен конкретной попытки, а не инстанса. Он отличает
 * и случай, когда задачу перехватил другой инстанс, и случай, когда её перехватил
 * тот же инстанс повторным захватом (зависший воркер восстановлением был возвращён
 * в очередь и захвачен заново) — сравнение по {@code workerId} второй сценарий
 * не поймало бы.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStatusUpdater {

    private final TaskRepository taskRepository;

    /** Промежуточный результат: доля выполнения, видна через REST по ходу работы. */
    @Transactional
    public void updateProgress(Long taskId, int progress, String claimToken) {
        int updated = taskRepository.updateProgress(taskId, progress, claimToken, Instant.now());
        warnIfSkipped(updated, taskId, "сохранение прогресса");
    }

    @Transactional
    public void markCompleted(Long taskId, String result, String claimToken) {
        int updated = taskRepository.markCompleted(taskId, result, claimToken, Instant.now());
        warnIfSkipped(updated, taskId, "запись успешного завершения");
    }

    @Transactional
    public void markFailed(Long taskId, String errorMessage, String claimToken) {
        int updated = taskRepository.markFailed(taskId, errorMessage, claimToken, Instant.now());
        warnIfSkipped(updated, taskId, "запись ошибки");
    }

    private void warnIfSkipped(int updatedRows, Long taskId, String action) {
        if (updatedRows == 0) {
            log.warn("Задача {} больше не принадлежит этой попытке — {} пропущено", taskId, action);
        }
    }
}
