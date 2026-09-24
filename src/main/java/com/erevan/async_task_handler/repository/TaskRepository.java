package com.erevan.async_task_handler.repository;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * Атомарно захватывает до {@code limit} задач в статусе NEW.
     * <p>
     * {@code FOR UPDATE SKIP LOCKED} заставляет PostgreSQL пропускать строки,
     * уже заблокированные другой транзакцией, вместо ожидания на них. За счёт
     * этого параллельные воркеры — в том числе в разных инстансах сервиса —
     * разбирают <i>непересекающиеся</i> наборы задач без единого конфликта,
     * и повторная обработка одной задачи становится невозможной (п.5 ТЗ).
     * <p>
     * Запрос намеренно нативный. Портируемый вариант через
     * {@code @Lock(PESSIMISTIC_WRITE)} с хинтом {@code jakarta.persistence.lock.timeout}
     * здесь не подходит: значение 0 у этого хинта означает NOWAIT — транзакция
     * упадёт с ошибкой на занятой строке вместо того, чтобы пропустить её.
     * SKIP LOCKED соответствует отдельному значению -2, и полагаться на это
     * соответствие менее очевидно, чем написать SQL явно.
     * <p>
     * Блокировка живёт до конца транзакции, поэтому вызывающий код обязан
     * держать её короткой: перевести задачи в IN_PROGRESS и выйти, а само
     * выполнение вести уже за пределами транзакции.
     *
     * @param limit сколько задач захватывать за раз — обычно свободная ёмкость пула
     * @return захваченные и заблокированные задачи, порядок по возрастанию id
     */
    @Query(value = """
            SELECT * FROM tasks
            WHERE status = 'NEW'
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Task> lockNewTasks(@Param("limit") int limit);

    /**
     * Захватывает задачи, зависшие в статусе IN_PROGRESS.
     * <p>
     * Признак зависания — слишком давний {@code heartbeat_at}: воркер обновляет
     * его вместе с каждым сохранением прогресса, поэтому порог может быть
     * коротким и не завязан на предельную длительность задачи.
     * <p>
     * SKIP LOCKED здесь нужен по той же причине, что и при обычном захвате:
     * восстановлением занимаются все инстансы сразу, и одну задачу не должны
     * поднимать двое.
     *
     * @param threshold момент, раньше которого задача считается зависшей
     */
    @Query(value = """
            SELECT * FROM tasks
            WHERE status = 'IN_PROGRESS'
              AND heartbeat_at < :threshold
            ORDER BY heartbeat_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Task> lockStuckTasks(@Param("threshold") Instant threshold, @Param("limit") int limit);

    /**
     * Сохраняет прогресс без чтения сущности: одно условное UPDATE вместо
     * SELECT + оптимистичная блокировка + ретрай.
     * <p>
     * Условие {@code status = 'IN_PROGRESS' AND claim_token = :claimToken}
     * само по себе атомарно и заменяет проверку владения — гонки нет,
     * а 0 обновлённых строк означает, что задача больше не принадлежит
     * этой попытке (перехвачена восстановлением или уже завершена).
     *
     * @return число обновлённых строк — 0 или 1
     */
    @Modifying
    @Query(value = """
            UPDATE tasks
            SET progress = :progress, heartbeat_at = :now
            WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claimToken
            """, nativeQuery = true)
    int updateProgress(@Param("id") Long id, @Param("progress") int progress,
                        @Param("claimToken") String claimToken, @Param("now") Instant now);

    /** Успешное завершение — атомарно, на тех же условиях, что и {@link #updateProgress}. */
    @Modifying
    @Query(value = """
            UPDATE tasks
            SET status = 'COMPLETED', progress = 100, result = :result, finished_at = :now
            WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claimToken
            """, nativeQuery = true)
    int markCompleted(@Param("id") Long id, @Param("result") String result,
                       @Param("claimToken") String claimToken, @Param("now") Instant now);

    /** Провал выполнения — атомарно, на тех же условиях, что и {@link #updateProgress}. */
    @Modifying
    @Query(value = """
            UPDATE tasks
            SET status = 'FAILED', error_message = :errorMessage, finished_at = :now
            WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claimToken
            """, nativeQuery = true)
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage,
                    @Param("claimToken") String claimToken, @Param("now") Instant now);

    /** Проверка повторной доставки сообщения из Kafka. */
    boolean existsByDedupKey(String dedupKey);

    /** Поиск по ключу сообщения Kafka — так клиент REST находит задачу по своему correlationKey. */
    Optional<Task> findByDedupKey(String dedupKey);

    List<Task> findByStatus(TaskStatus status);

    long countByStatus(TaskStatus status);
}
