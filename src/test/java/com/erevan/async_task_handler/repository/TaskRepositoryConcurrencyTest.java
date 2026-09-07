package com.erevan.async_task_handler.repository;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет главное свойство механизма захвата: параллельные воркеры
 * получают непересекающиеся задачи и не блокируют друг друга.
 */
class TaskRepositoryConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearTasks() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("SKIP LOCKED: две параллельные транзакции разбирают разные задачи")
    void concurrentTransactionsClaimDisjointTasks() throws Exception {
        int totalTasks = 10;
        int batchSize = 5;
        taskRepository.saveAll(
                IntStream.rangeClosed(1, totalTasks)
                        .mapToObj(i -> new Task("task-" + i, 100L))
                        .toList());

        // Барьер держит обе транзакции открытыми одновременно. Без него они
        // могли бы отработать последовательно, и тест не отличил бы
        // SKIP LOCKED от обычного FOR UPDATE.
        CyclicBarrier bothClaimed = new CyclicBarrier(2);
        Callable<List<Long>> claimBatch = () -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    List<Long> claimed = taskRepository.lockNewTasks(batchSize).stream()
                            .map(Task::getId)
                            .toList();
                    try {
                        bothClaimed.await(15, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Вторая транзакция не дошла до барьера — похоже, "
                                        + "она заблокировалась на занятых строках вместо того, "
                                        + "чтобы их пропустить", e);
                    }
                    return claimed;
                });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> first = workers.submit(claimBatch);
            Future<List<Long>> second = workers.submit(claimBatch);

            List<Long> firstBatch = first.get(30, TimeUnit.SECONDS);
            List<Long> secondBatch = second.get(30, TimeUnit.SECONDS);

            assertThat(firstBatch).hasSize(batchSize);
            assertThat(secondBatch).hasSize(batchSize);
            assertThat(firstBatch).doesNotContainAnyElementsOf(secondBatch);
            assertThat(Stream.concat(firstBatch.stream(), secondBatch.stream()).distinct())
                    .as("вместе воркеры разобрали всю очередь без дублей")
                    .hasSize(totalTasks);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("Захват уважает limit и берёт самые старые задачи")
    void claimRespectsLimitAndOrder() {
        taskRepository.saveAll(
                IntStream.rangeClosed(1, 5)
                        .mapToObj(i -> new Task("task-" + i, 100L))
                        .toList());
        List<Long> allIds = taskRepository.findByStatus(TaskStatus.NEW).stream()
                .map(Task::getId)
                .sorted()
                .toList();

        List<Long> claimed = new TransactionTemplate(transactionManager)
                .execute(status -> taskRepository.lockNewTasks(3).stream()
                        .map(Task::getId)
                        .toList());

        assertThat(claimed).containsExactlyElementsOf(allIds.subList(0, 3));
    }

    @Test
    @DisplayName("Захватываются только задачи в статусе NEW")
    void claimSkipsTasksInOtherStatuses() {
        Task inProgress = new Task("busy", 100L);
        inProgress.setStatus(TaskStatus.IN_PROGRESS);
        Task completed = new Task("done", 100L);
        completed.setStatus(TaskStatus.COMPLETED);
        Task fresh = new Task("fresh", 100L);
        taskRepository.saveAll(List.of(inProgress, completed, fresh));

        List<Long> claimed = new TransactionTemplate(transactionManager)
                .execute(status -> taskRepository.lockNewTasks(10).stream()
                        .map(Task::getId)
                        .toList());

        assertThat(claimed).containsExactly(fresh.getId());
    }
}
