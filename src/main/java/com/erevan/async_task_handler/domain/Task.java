package com.erevan.async_task_handler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.proxy.HibernateProxy;

import java.time.Instant;
import java.util.Objects;

/**
 * Задача, выполняемая пулом воркеров.
 * <p>
 * Схему таблицы ведёт Liquibase (см. db/changelog); Hibernate работает
 * в режиме ddl-auto: validate и только сверяет с ней маппинг.
 */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Длительность имитации работы воркером, миллисекунды. */
    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    /** Промежуточный результат: доля выполнения 0..100, обновляется по ходу. */
    @Column(nullable = false)
    private int progress;

    @Column(columnDefinition = "text")
    private String result;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /** Идентификатор инстанса, захватившего задачу. */
    @Column(name = "worker_id", length = 120)
    private String workerId;

    /** Сколько раз задачу возвращали в очередь после зависания. */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /**
     * Ключ сообщения Kafka, по которому отсеиваются повторные доставки.
     * Может отсутствовать: сообщение вправе прийти без ключа.
     */
    @Column(name = "dedup_key", length = 255)
    private String dedupKey;

    /**
     * Токен конкретной попытки выполнения, выдаётся заново при каждом захвате.
     * <p>
     * В отличие от {@code workerId} (это идентификатор инстанса, а не попытки)
     * отличает и повторный захват той же задачи тем же инстансом: у зависшего
     * воркера токен остаётся старым, у новой попытки — свежий, поэтому очнувшийся
     * воркер не может затереть результат уже выполняющейся параллельно попытки.
     */
    @Column(name = "claim_token", length = 36)
    private String claimToken;

    /** Отметка последней активности воркера; по ней ищутся зависшие задачи. */
    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    /**
     * Оптимистичная блокировка. Захват задачи защищён на уровне БД через
     * SKIP LOCKED, а версия страхует путь обновления статуса: если задачу
     * параллельно тронул кто-то ещё, коммит упадёт вместо тихой перезаписи.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public Task(String name, Long durationMs) {
        this.name = name;
        this.durationMs = durationMs;
        this.status = TaskStatus.NEW;
    }

    /** Захват воркером: NEW → IN_PROGRESS с выдачей нового токена попытки. */
    public void claim(String workerId, String claimToken, Instant now) {
        this.status = TaskStatus.IN_PROGRESS;
        this.workerId = workerId;
        this.claimToken = claimToken;
        this.startedAt = now;
        this.heartbeatAt = now;
    }

    /** Возврат в очередь без изменения счётчика попыток — нераспределённая задача. */
    public void release() {
        this.status = TaskStatus.NEW;
        this.workerId = null;
        this.claimToken = null;
        this.startedAt = null;
        this.heartbeatAt = null;
    }

    /** Возврат в очередь зависшей задачи: попытка засчитана, прогресс сброшен. */
    public void requeueAfterStuckRecovery() {
        this.retryCount++;
        this.progress = 0;
        release();
    }

    /** Финальный провал — как по итогам исполнения, так и по исчерпанию попыток восстановления. */
    public void fail(String errorMessage, Instant now) {
        this.status = TaskStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }

    /** Задача считается своей только если она ещё выполняется и токен совпадает с текущей попыткой. */
    public boolean isOwnedInProgressBy(String claimToken) {
        return status == TaskStatus.IN_PROGRESS
                && claimToken != null
                && claimToken.equals(this.claimToken);
    }

    /*
     * equals/hashCode намеренно написаны руками, а не взяты из @Data:
     * сгенерированные Lombok'ом версии сравнивают все поля, из-за чего
     * хэш сущности меняется при каждом обновлении статуса, и объект
     * теряется в любой HashMap/HashSet. Сравниваем по идентификатору,
     * а хэш держим константным в пределах класса.
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        // Под ленивым проксированием getClass() вернёт класс прокси, а не Task
        Class<?> thisType = this instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : getClass();
        Class<?> otherType = o instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : o.getClass();
        if (thisType != otherType) {
            return false;
        }
        Task other = (Task) o;
        // Пока идентификатор не присвоен, две новые сущности не равны друг другу
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
                : getClass().hashCode();
    }
}
