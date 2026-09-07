package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.service.TaskClaimService.ClaimedTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Планировщик: периодически опрашивает очередь и раздаёт задачи пулу воркеров.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TaskDispatcher {

    private final TaskClaimService claimService;
    private final TaskRunner taskRunner;
    private final WorkerPool workerPool;

    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms}")
    public void dispatchPendingTasks() {
        int freeSlots = workerPool.freeSlots();
        if (freeSlots == 0) {
            // Пул занят полностью: захватывать задачи сейчас означало бы
            // перевести их в IN_PROGRESS и оставить ждать в очереди исполнителя
            return;
        }

        List<ClaimedTask> claimed = claimService.claim(freeSlots);
        if (claimed.isEmpty()) {
            return;
        }

        /*
         * Задачи отдаются в пул только здесь — после того, как транзакция
         * захвата уже закоммичена (она закончилась вместе с вызовом claim).
         * Постановка в пул изнутри транзакции была бы гонкой: воркер успел бы
         * прочитать задачу до фиксации перевода в IN_PROGRESS и увидел бы
         * её всё ещё в статусе NEW.
         */
        log.info("Взято в работу задач: {} из {} свободных слотов", claimed.size(), freeSlots);
        for (ClaimedTask task : claimed) {
            workerPool.submit(() -> taskRunner.run(task));
        }
    }
}
