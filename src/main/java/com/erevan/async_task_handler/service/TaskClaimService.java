package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.metrics.TaskMetrics;
import com.erevan.async_task_handler.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
        List<ClaimedTask> result = tasks.stream()
                .map(task -> {
                    String claimToken = UUID.randomUUID().toString();
                    // Явный save не нужен: сущность управляемая, изменения уйдут
                    // в БД автоматической проверкой изменений при коммите
                    task.claim(workerIdentity.id(), claimToken, now);
                    return new ClaimedTask(task.getId(), task.getDurationMs(), claimToken);
                })
                .toList();

        taskMetrics.recordClaimed(result.size());
        log.debug("Захвачено задач: {} (id: {})", result.size(), result.stream().map(ClaimedTask::id).toList());
        return result;
    }

    /**
     * Возвращает в очередь захваченные задачи, которые не будут доведены до конца
     * этой попыткой.
     * <p>
     * Два случая: постановка в пул сорвалась на середине пачки (исполнитель
     * отклонил задачу из-за начавшейся остановки сервиса), либо воркер поймал
     * {@code InterruptedException} при остановке сервиса на середине работы.
     * Без возврата задача провисела бы до срабатывания восстановления зависших.
     * <p>
     * Проверка токена попытки обязательна: пока мы сюда добрались, задачу мог
     * перехватить кто-то ещё (включая повторный захват этим же инстансом),
     * и сбрасывать чужую выполняющуюся попытку в NEW нельзя.
     */
    @Transactional
    public void releaseClaim(List<ClaimedTask> claimed) {
        if (claimed.isEmpty()) {
            return;
        }

        Map<Long, String> claimTokenById = claimed.stream()
                .collect(Collectors.toMap(ClaimedTask::id, ClaimedTask::claimToken));
        List<Task> tasks = taskRepository.findAllById(claimTokenById.keySet());
        int released = 0;
        for (Task task : tasks) {
            if (!task.isOwnedInProgressBy(claimTokenById.get(task.getId()))) {
                continue;
            }
            task.release();
            released++;
        }
        log.warn("Возвращено в очередь задач: {} из {}", released, claimed.size());
    }

    /**
     * Данные захваченной задачи, передаваемые воркеру.
     * <p>
     * Передаётся именно снимок, а не сущность: объект уходит в другой поток,
     * где персистентный контекст уже закрыт, и обращение к ленивому состоянию
     * такой сущности привело бы к ошибке. {@code claimToken} — токен именно
     * этой попытки, по нему сверяется владение на всех последующих обновлениях.
     */
    public record ClaimedTask(Long id, long durationMs, String claimToken) {}
}
