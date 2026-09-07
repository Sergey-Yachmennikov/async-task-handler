package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Пул воркеров и учёт его свободной ёмкости.
 * <p>
 * Ёмкость нужна планировщику, чтобы забирать из БД ровно столько задач,
 * сколько прямо сейчас может начать выполняться. Без этого задачи копились бы
 * в очереди исполнителя, числясь в базе как IN_PROGRESS ещё до того, как их
 * кто-то начал делать.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerPool {

    private final ExecutorService executor;
    private final Semaphore freeSlots;
    private final int poolSize;

    public WorkerPool(AppProperties properties, MeterRegistry meterRegistry) {
        this.poolSize = properties.worker().poolSize();
        this.freeSlots = new Semaphore(poolSize);
        this.executor = Executors.newFixedThreadPool(poolSize, namedThreadFactory());

        /*
         * Занятость пула отдаётся именно измерителем, а не счётчиком: значение
         * не накапливается, а вычисляется в момент опроса. Так метрика всегда
         * отражает текущее состояние, даже если часть событий была пропущена.
         */
        Gauge.builder("worker.pool.busy", this, pool -> pool.poolSize - pool.freeSlots.availablePermits())
                .description("Воркеры, занятые выполнением задач")
                .register(meterRegistry);
        Gauge.builder("worker.pool.size", this, pool -> pool.poolSize)
                .description("Всего воркеров в пуле")
                .register(meterRegistry);

        log.info("Пул воркеров инициализирован: {} потоков", poolSize);
    }

    /** Сколько задач можно запустить прямо сейчас. */
    public int freeSlots() {
        return freeSlots.availablePermits();
    }

    public int poolSize() {
        return poolSize;
    }

    /**
     * Ставит задачу в пул, занимая один слот до её завершения.
     * <p>
     * Разрешение забирается непосредственно перед постановкой и всегда доступно:
     * захватывать разрешения может только поток планировщика, и он берёт их
     * не больше, чем показал {@link #freeSlots()}, а возврат разрешений
     * ёмкость лишь увеличивает.
     */
    public void submit(Runnable job) {
        freeSlots.acquireUninterruptibly();
        try {
            executor.execute(() -> {
                try {
                    job.run();
                } finally {
                    freeSlots.release();
                }
            });
        } catch (RuntimeException e) {
            // Исполнитель отклонил задачу (например, идёт остановка) —
            // слот нужно вернуть, иначе ёмкость пула утечёт безвозвратно
            freeSlots.release();
            throw e;
        }
    }

    /**
     * Останавливает пул, давая текущим задачам доработать.
     * <p>
     * Задачи, не уложившиеся в отведённое время, прерываются: их статус
     * останется IN_PROGRESS до перезапуска обработки.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Остановка пула воркеров, выполняется задач: {}", poolSize - freeSlots.availablePermits());
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Воркеры не завершились за 30с, прерываем принудительно");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            // Осмысленные имена потоков сильно упрощают чтение логов и thread dump
            thread.setName("task-worker-" + counter.incrementAndGet());
            return thread;
        };
    }
}
