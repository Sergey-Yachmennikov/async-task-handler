package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.repository.TaskRepository;
import com.erevan.async_task_handler.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Защита от записи результата в задачу, которая больше не принадлежит
 * текущей попытке.
 * <p>
 * Сценарий из жизни: воркер подвис, восстановление сочло задачу зависшей
 * и вернуло её в очередь, задачу захватил другой инстанс. Очнувшийся воркер
 * не должен затирать чужую выполняющуюся попытку.
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

    @Autowired
    private WorkerIdentity workerIdentity;

    @BeforeEach
    void clearTasks() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Результат своей задачи записывается")
    void ownTaskIsCompleted() {
        Task task = taskRepository.save(inProgressOwnedBy(workerIdentity.id()));

        statusUpdater.markCompleted(task.getId(), "готово");

        assertThat(taskRepository.findById(task.getId())).get().satisfies(updated -> {
            assertThat(updated.getStatus()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(updated.getProgress()).isEqualTo(100);
            assertThat(updated.getResult()).isEqualTo("готово");
        });
    }

    @Test
    @DisplayName("Задачу, перехваченную другим инстансом, завершать нельзя")
    void completionOfTaskOwnedByAnotherInstanceIsSkipped() {
        Task task = taskRepository.save(inProgressOwnedBy("другой-инстанс"));

        statusUpdater.markCompleted(task.getId(), "результат чужой попытки");

        assertThat(taskRepository.findById(task.getId())).get().satisfies(untouched -> {
            assertThat(untouched.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(untouched.getResult()).isNull();
            assertThat(untouched.getWorkerId()).isEqualTo("другой-инстанс");
        });
    }

    @Test
    @DisplayName("Задачу, возвращённую восстановлением в очередь, завершать нельзя")
    void completionOfRequeuedTaskIsSkipped() {
        // Именно так выглядит задача после StuckTaskRecoveryService
        Task requeued = inProgressOwnedBy(workerIdentity.id());
        requeued.setStatus(TaskStatus.NEW);
        requeued.setWorkerId(null);
        requeued.setStartedAt(null);
        Task task = taskRepository.save(requeued);

        statusUpdater.markCompleted(task.getId(), "запоздавший результат");
        statusUpdater.updateProgress(task.getId(), 80);

        assertThat(taskRepository.findById(task.getId())).get().satisfies(untouched -> {
            assertThat(untouched.getStatus()).isEqualTo(TaskStatus.NEW);
            assertThat(untouched.getProgress()).isZero();
            assertThat(untouched.getResult()).isNull();
        });
    }

    @Test
    @DisplayName("Нераспределённые задачи возвращаются в очередь")
    void unassignedTasksAreReleasedBackToQueue() {
        List<Task> claimed = taskRepository.saveAll(List.of(
                inProgressOwnedBy(workerIdentity.id()),
                inProgressOwnedBy(workerIdentity.id())));

        claimService.releaseClaim(claimed.stream().map(Task::getId).toList());

        assertThat(taskRepository.findAll()).allSatisfy(released -> {
            assertThat(released.getStatus()).isEqualTo(TaskStatus.NEW);
            assertThat(released.getWorkerId()).isNull();
            assertThat(released.getStartedAt()).isNull();
        });
    }

    @Test
    @DisplayName("Возврат в очередь не трогает задачи чужого инстанса")
    void releaseLeavesTasksOfOtherInstancesAlone() {
        Task foreign = taskRepository.save(inProgressOwnedBy("другой-инстанс"));

        claimService.releaseClaim(List.of(foreign.getId()));

        assertThat(taskRepository.findById(foreign.getId())).get().satisfies(untouched -> {
            assertThat(untouched.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(untouched.getWorkerId()).isEqualTo("другой-инстанс");
        });
    }

    private Task inProgressOwnedBy(String workerId) {
        Task task = new Task("owned-task", 1_000L);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(Instant.now());
        task.setWorkerId(workerId);
        return task;
    }
}
