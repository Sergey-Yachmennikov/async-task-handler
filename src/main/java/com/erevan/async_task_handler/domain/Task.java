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
