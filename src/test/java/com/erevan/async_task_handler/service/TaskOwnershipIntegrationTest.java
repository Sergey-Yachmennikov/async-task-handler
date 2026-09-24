package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.repository.TaskRepository;
import com.erevan.async_task_handler.service.TaskClaimService.ClaimedTask;
import com.erevan.async_task_handler.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Защита от записи результата в задачу, которая больше не принадлежит
 * текущей попытке.
 * <p>
 * Сценарий из жизни: воркер подвис — долгая пауза сборщика мусора,
 * приостановленная виртуалка, — восстановление сочло задачу зависшей
 * и вернуло в очередь, её захватил другой (или тот же) инстанс и начал
 * выполнять заново. Очнувшийся воркер не должен затирать чужую выполняющуюся
 * попытку. Владение проверяется по {@code claim_token} — токену конкретной
 * попытки, а не по {@code workerId}: этого достаточно, чтобы поймать в том
 * числе повторный захват тем же самым инстансом.
 * <p>
 * Планировщик выключен базовым классом, поэтому статусы меняются только
 * вызовами из теста.
 */
class TaskOwnershipIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskStatusUpdater statusUpdater;

    @Autowired
    private TaskClaimService claimService;

    @BeforeEach
    void clearTasks() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Результат своей задачи записывается")
    void ownTaskIsCompleted() {
        String claimToken = UUID.randomUUID().toString();
        Task task = taskRepository.save(inProgressWithToken(claimToken));

        statusUpdater.markCompleted(task.getId(), "готово", claimToken);

        assertThat(taskRepository.findById(task.getId())).get().satisfies(updated -> {
            assertThat(updated.getStatus()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(updated.getProgress()).isEqualTo(100);
            assertThat(updated.getResult()).isEqualTo("готово");
        });
    }

    @Test
    @DisplayName("Задачу с чужим токеном попытки завершать нельзя")
    void completionWithForeignClaimTokenIsSkipped() {
        Task task = taskRepository.save(inProgressWithToken(UUID.randomUUID().toString()));

        // Токен не совпадает с тем, что записан у задачи — как если бы её
        // перехватила другая попытка, включая повторный захват тем же инстансом
        statusUpdater.markCompleted(task.getId(), "результат чужой попытки", UUID.randomUUID().toString());

        assertThat(taskRepository.findById(task.getId())).get().satisfies(untouched -> {
            assertThat(untouched.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(untouched.getResult()).isNull();
        });
    }

    @Test
    @DisplayName("Задачу, возвращённую восстановлением в очередь, завершать нельзя")
    void completionOfRequeuedTaskIsSkipped() {
        // Именно так выглядит задача после StuckTaskRecoveryService: статус NEW,
        // токен предыдущей попытки очищен
        String staleToken = UUID.randomUUID().toString();
        Task requeued = inProgressWithToken(staleToken);
        requeued.release();
        Task task = taskRepository.save(requeued);

        statusUpdater.markCompleted(task.getId(), "запоздавший результат", staleToken);
        statusUpdater.updateProgress(task.getId(), 80, staleToken);

        assertThat(taskRepository.findById(task.getId())).get().satisfies(untouched -> {
            assertThat(untouched.getStatus()).isEqualTo(TaskStatus.NEW);
            assertThat(untouched.getProgress()).isZero();
            assertThat(untouched.getResult()).isNull();
        });
    }

    @Test
    @DisplayName("Нераспределённые задачи возвращаются в очередь")
    void unassignedTasksAreReleasedBackToQueue() {
        String tokenA = UUID.randomUUID().toString();
        String tokenB = UUID.randomUUID().toString();
        Task taskA = taskRepository.save(inProgressWithToken(tokenA));
        Task taskB = taskRepository.save(inProgressWithToken(tokenB));

        claimService.releaseClaim(List.of(
                new ClaimedTask(taskA.getId(), taskA.getDurationMs(), tokenA),
                new ClaimedTask(taskB.getId(), taskB.getDurationMs(), tokenB)));

        assertThat(taskRepository.findAll()).allSatisfy(released -> {
            assertThat(released.getStatus()).isEqualTo(TaskStatus.NEW);
            assertThat(released.getWorkerId()).isNull();
            assertThat(released.getStartedAt()).isNull();
        });
    }

    @Test
    @DisplayName("Возврат в очередь не трогает задачи с чужим токеном попытки")
    void releaseLeavesTasksWithForeignClaimTokenAlone() {
        Task foreign = taskRepository.save(inProgressWithToken(UUID.randomUUID().toString()));

        // Токен в запросе не совпадает с тем, что реально записан у задачи
        claimService.releaseClaim(List.of(
                new ClaimedTask(foreign.getId(), foreign.getDurationMs(), UUID.randomUUID().toString())));

        assertThat(taskRepository.findById(foreign.getId())).get().satisfies(untouched ->
                assertThat(untouched.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS));
    }

    private Task inProgressWithToken(String claimToken) {
        Task task = new Task("owned-task", 1_000L);
        task.claim("test-instance", claimToken, Instant.now());
        return task;
    }
}
