package com.erevan.async_task_handler.repository;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

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
     * Задача зависает, когда инстанс упал, не успев дописать финальный статус:
     * никто её больше не выполняет, но и в очередь она не вернётся — строка
     * так и останется в IN_PROGRESS навсегда. Признак — слишком давний
     * {@code started_at}.
     * <p>
     * SKIP LOCKED здесь нужен по той же причине, что и при обычном захвате:
     * восстановлением занимаются все инстансы сразу, и одну задачу не должны
     * поднимать двое.
     *
     * @param threshold момент, раньше которого начатая задача считается зависшей
     */
    @Query(value = """
            SELECT * FROM tasks
            WHERE status = 'IN_PROGRESS'
              AND started_at < :threshold
            ORDER BY started_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Task> lockStuckTasks(@Param("threshold") Instant threshold, @Param("limit") int limit);

    /** Проверка повторной доставки сообщения из Kafka. */
    boolean existsByDedupKey(String dedupKey);

    List<Task> findByStatus(TaskStatus status);

    long countByStatus(TaskStatus status);
}
