package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.repository.TaskRepository;
import com.erevan.async_task_handler.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Восстановление задач, зависших после падения инстанса.
 * <p>
 * Воркеры выключены: проверяется исключительно работа восстановления,
 * а выполнение задач увело бы их из проверяемых статусов.
 */
@TestPropertySource(properties = {
        "app.recovery.enabled=true",
        // Порог занижен до трёх секунд, чтобы не ждать в тесте штатные 15 минут.
        // Ниже опускать не стоит: тест «недавно начатой» задачи должен успеть
        // отработать несколько циклов восстановления, не выйдя за этот порог
        "app.recovery.stuck-timeout-ms=3000",
        "app.recovery.interval-ms=200",
        "app.recovery.max-retries=2"
})
// Контекст закрывается вместе с классом: планировщик восстановления с таким
// коротким порогом испортил бы данные последующих тестов
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StuckTaskRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void clearTasks() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Зависшая задача возвращается в очередь с инкрементом попыток")
    void stuckTaskReturnsToQueue() {
        Task stuck = taskRepository.save(inProgressSince(Instant.now().minusSeconds(60), 0, 40));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(taskRepository.findById(stuck.getId()))
                        .get()
                        .satisfies(recovered -> {
                            assertThat(recovered.getStatus()).isEqualTo(TaskStatus.NEW);
                            assertThat(recovered.getRetryCount()).isEqualTo(1);
                            // Следы прошлой попытки должны быть стёрты,
                            // иначе задача вернётся с чужим прогрессом
                            assertThat(recovered.getProgress()).isZero();
                            assertThat(recovered.getWorkerId()).isNull();
                            assertThat(recovered.getStartedAt()).isNull();
                        }));
    }

    @Test
    @DisplayName("Исчерпав попытки, зависшая задача признаётся провалившейся")
    void exhaustedTaskBecomesFailed() {
        Task stuck = taskRepository.save(inProgressSince(Instant.now().minusSeconds(60), 2, 70));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(taskRepository.findById(stuck.getId()))
                        .get()
                        .satisfies(failed -> {
                            assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
                            assertThat(failed.getErrorMessage()).contains("исчерпала попытки");
                            assertThat(failed.getFinishedAt()).isNotNull();
                        }));
    }

    @Test
    @DisplayName("Задача, начатая недавно, не считается зависшей")
    void freshlyStartedTaskIsLeftAlone() throws InterruptedException {
        Task fresh = taskRepository.save(inProgressSince(Instant.now(), 0, 10));

        // Секунда — это пять проходов восстановления при интервале 200 мс,
        // и всё ещё втрое меньше порога зависания. Если задачу заберут,
        // значит порог не проверяется вовсе
        Thread.sleep(1_000);

        assertThat(taskRepository.findById(fresh.getId()))
                .get()
                .satisfies(untouched -> {
                    assertThat(untouched.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
                    assertThat(untouched.getRetryCount()).isZero();
                });
    }

    private Task inProgressSince(Instant startedAt, int retryCount, int progress) {
        Task task = new Task("stuck-task", 1_000L);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(startedAt);
        task.setRetryCount(retryCount);
        task.setProgress(progress);
        task.setWorkerId("dead-instance");
        return task;
    }
}
