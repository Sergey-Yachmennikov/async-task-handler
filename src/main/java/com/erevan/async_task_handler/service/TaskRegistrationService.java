package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.mapper.TaskMapper;
import com.erevan.async_task_handler.metrics.TaskMetrics;
import com.erevan.async_task_handler.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Регистрация задачи в системе, п.1 ТЗ: сохранение принятого сообщения
 * в БД со статусом NEW, откуда его заберёт пул воркеров.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRegistrationService {

    private final TaskRepository taskRepository;
    private final TaskMapper taskMapper;
    private final TaskMetrics taskMetrics;

    /**
     * Сохраняет задачу, отсеивая повторные доставки одного сообщения.
     * <p>
     * Kafka гарантирует доставку «хотя бы один раз»: после сбоя консьюмера
     * или перебалансировки группы сообщение будет прочитано снова. Без защиты
     * это порождало бы дубликат задачи при каждой такой повторной доставке.
     * <p>
     * Защита двухуровневая. Проверка {@code existsByDedupKey} отсекает
     * очевидные повторы, а уникальное ограничение в БД закрывает гонку, когда
     * два инстанса проверили одновременно и оба увидели, что задачи ещё нет.
     * Полагаться на одну лишь проверку нельзя — между ней и вставкой есть окно.
     *
     * @param dedupKey ключ сообщения Kafka; может быть null, тогда
     *                 дедуплицировать не по чему и задача сохраняется как есть
     * @return сохранённая задача либо пусто, если это повторная доставка
     */
    /*
     * Метод намеренно не помечен @Transactional.
     *
     * Нарушение уникального ограничения нужно перехватить, а сделать это
     * внутри собственной транзакции невозможно: после такой ошибки транзакция
     * помечена только на откат, и попытка нормально завершиться приведёт
     * к UnexpectedRollbackException при коммите.
     *
     * Здесь это и не требуется: save() у репозитория Spring Data сам по себе
     * транзакционный, вставка атомарна, а исключение приходит уже за границей
     * транзакции — там его можно спокойно обработать. Общая транзакция на оба
     * действия смысла не имеет, корректность обеспечивает ограничение в БД.
     */
    public Optional<Task> register(TaskRequestDto request, String dedupKey) {
        if (dedupKey != null && taskRepository.existsByDedupKey(dedupKey)) {
            log.info("Повторная доставка отброшена: dedupKey={}", dedupKey);
            taskMetrics.recordDuplicateSkipped();
            return Optional.empty();
        }

        Task task = taskMapper.toEntity(request);
        task.setDedupKey(dedupKey);
        try {
            Task saved = taskRepository.save(task);
            log.info("Задача зарегистрирована: id={}, name={}, durationMs={}",
                    saved.getId(), saved.getName(), saved.getDurationMs());
            taskMetrics.recordRegistered();
            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            // Сюда попадёт любое нарушение целостности — CHECK на duration,
            // NOT NULL, длина строки, — а не только наш уникальный индекс.
            // Без проверки имени ограничения настоящий баг тихо превратился бы
            // в "дубль", и сообщение пропало бы вместо ухода в DLT
            if (!isDedupKeyViolation(e)) {
                throw e;
            }
            log.info("Повторная доставка отброшена ограничением БД: dedupKey={}", dedupKey);
            taskMetrics.recordDuplicateSkipped();
            return Optional.empty();
        }
    }

    private boolean isDedupKeyViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && "uq_tasks_dedup_key".equals(cve.getConstraintName());
    }
}
