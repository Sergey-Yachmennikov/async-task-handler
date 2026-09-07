package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.repository.TaskRepository;
import com.erevan.async_task_handler.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Асинхронное выполнение задач пулом воркеров, пп. 3-4 ТЗ.
 */
@TestPropertySource(properties = {
        // Единственный класс, где планировщик включён — см. AbstractIntegrationTest
        "app.worker.enabled=true",
        "app.worker.pool-size=4",
        "app.worker.poll-interval-ms=100",
        "app.worker.progress-update-interval-ms=100"
})
// Контекст закрывается вместе с классом: иначе его воркеры продолжили бы
// разбирать задачи, создаваемые последующими тестовыми классами
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskExecutionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private WorkerPool workerPool;

    @BeforeEach
    void clearTasks() {
        // Пакетное удаление вместо deleteAll(): не сверяет @Version построчно,
        // поэтому не конфликтует с задачей, дописывающей свой статус
        taskRepository.deleteAllInBatch();
    }

    /**
     * Дожидается опустошения пула перед следующим тестом.
     * <p>
     * Тест завершается, как только нужный статус появился в БД, но воркер
     * в этот момент ещё может доделывать свою работу. Без ожидания очистка
     * данных следующим тестом выдернула бы у него строку из-под ног.
     */
    @AfterEach
    void awaitWorkersIdle() {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> workerPool.freeSlots() == workerPool.poolSize());
    }

    @Test
    @DisplayName("Задача проходит путь NEW → IN_PROGRESS → COMPLETED")
    void taskReachesCompletedState() {
        Task task = taskRepository.save(new Task("report", 500L));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(taskRepository.findById(task.getId()))
                        .get()
                        .satisfies(finished -> {
                            assertThat(finished.getStatus()).isEqualTo(TaskStatus.COMPLETED);
                            assertThat(finished.getProgress()).isEqualTo(100);
                            assertThat(finished.getResult()).isNotBlank();
                            assertThat(finished.getErrorMessage()).isNull();
                            assertThat(finished.getStartedAt()).isNotNull();
                            assertThat(finished.getFinishedAt()).isNotNull();
                            // Проставляется при захвате — по нему видно,
                            // какой инстанс выполнил задачу
                            assertThat(finished.getWorkerId()).isNotBlank();
                        }));
    }

    @Test
    @DisplayName("Промежуточный прогресс попадает в БД до завершения задачи")
    void intermediateProgressIsPersisted() {
        Task task = taskRepository.save(new Task("long-running", 3_000L));

        // Ловим задачу в середине выполнения: статус ещё IN_PROGRESS,
        // но прогресс уже сдвинулся с нуля
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(taskRepository.findById(task.getId()))
                        .get()
                        .satisfies(running -> {
                            assertThat(running.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
                            assertThat(running.getProgress()).isBetween(1, 99);
                        }));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(taskRepository.findById(task.getId()))
                        .get()
                        .extracting(Task::getStatus)
                        .isEqualTo(TaskStatus.COMPLETED));
    }

    @Test
    @DisplayName("Пул выполняет задачи параллельно, а не по очереди")
    void tasksRunInParallel() {
        int taskCount = 8;
        long durationMs = 1_000L;
        taskRepository.saveAll(IntStream.rangeClosed(1, taskCount)
                .mapToObj(i -> new Task("parallel-" + i, durationMs))
                .toList());

        long startedAt = System.currentTimeMillis();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(taskRepository.countByStatus(TaskStatus.COMPLETED))
                        .isEqualTo(taskCount));
        long elapsed = System.currentTimeMillis() - startedAt;

        /*
         * При пуле из 4 потоков 8 задач по секунде занимают две волны, около 2с.
         * Последовательное выполнение потребовало бы 8с — порог в 6с надёжно
         * разделяет эти два случая, оставляя запас на планировщик и БД.
         */
        assertThat(elapsed)
                .as("8 задач по %d мс на пуле из 4 потоков", durationMs)
                .isLessThan(6_000L);

        List<Task> tasks = taskRepository.findByStatus(TaskStatus.COMPLETED);
        assertThat(tasks).hasSize(taskCount)
                .allSatisfy(task -> assertThat(task.getProgress()).isEqualTo(100));
    }
}
