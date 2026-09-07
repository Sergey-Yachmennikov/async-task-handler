package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.metrics.TaskMetrics;
import com.erevan.async_task_handler.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Захват задач в работу — единственное место, где задача переходит NEW → IN_PROGRESS.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskClaimService {

    private final TaskRepository taskRepository;
    private final WorkerIdentity workerIdentity;
    private final TaskMetrics taskMetrics;

    /**
     * Забирает под себя до {@code limit} задач.
     * <p>
     * Транзакция намеренно короткая: внутри только блокировка строк и смена
     * статуса. Само выполнение вынесено наружу, потому что удерживать
     * блокировку БД на всё время работы задачи означало бы занимать соединение
     * из пула на секунды и мешать остальным.
     *
     * @return сведения о захваченных задачах, достаточные для выполнения
     */
    @Transactional
    public List<ClaimedTask> claim(int limit) {
        List<Task> tasks = taskRepository.lockNewTasks(limit);
        if (tasks.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();
        for (Task task : tasks) {
            task.setStatus(TaskStatus.IN_PROGRESS);
            task.setStartedAt(now);
            task.setWorkerId(workerIdentity.id());
        }
        // Явный save не нужен: сущности управляемые, изменения уйдут
        // в БД автоматической проверкой изменений при коммите

        taskMetrics.recordClaimed(tasks.size());
        log.debug("Захвачено задач: {} (id: {})", tasks.size(), tasks.stream().map(Task::getId).toList());
        return tasks.stream()
                .map(task -> new ClaimedTask(task.getId(), task.getDurationMs()))
                .toList();
    }

    /**
     * Возвращает в очередь задачи, которые были захвачены, но так и не попали
     * в пул воркеров.
     * <p>
     * Нужно, когда постановка в пул сорвалась на середине пачки — например,
     * исполнитель отклонил задачу из-за начавшейся остановки сервиса.
     * Эти задачи уже помечены IN_PROGRESS, но выполнять их некому: без возврата
     * они провисели бы до срабатывания восстановления зависших, то есть
     * четверть часа при настройках по умолчанию.
     * <p>
     * Проверка владельца обязательна: пока мы сюда добрались, задачу мог
     * перехватить кто-то ещё, и сбрасывать чужую работу в NEW нельзя.
     */
    @Transactional
    public void releaseClaim(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }

        List<Task> tasks = taskRepository.findAllById(taskIds);
        int released = 0;
        for (Task task : tasks) {
            if (task.getStatus() != TaskStatus.IN_PROGRESS
                    || !workerIdentity.id().equals(task.getWorkerId())) {
                continue;
            }
            task.setStatus(TaskStatus.NEW);
            task.setStartedAt(null);
            task.setWorkerId(null);
            released++;
        }
        log.warn("Возвращено в очередь задач: {} из {}", released, taskIds.size());
    }

    /**
     * Данные захваченной задачи, передаваемые воркеру.
     * <p>
     * Передаётся именно снимок, а не сущность: объект уходит в другой поток,
     * где персистентный контекст уже закрыт, и обращение к ленивому состоянию
     * такой сущности привело бы к ошибке.
     */
    public record ClaimedTask(Long id, long durationMs) {}
}
