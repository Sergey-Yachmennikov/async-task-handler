package com.erevan.async_task_handler.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Страхует связь между размером пула соединений и числом воркеров.
 * <p>
 * Пул обязан быть больше {@code worker.pool-size}: соединения нужны ещё
 * планировщику и REST-слою, помимо самих воркеров. Числа заданы в двух
 * независимых местах конфигурации, и ничего не мешает изменить одно,
 * забыв про другое — тогда пул начнёт незаметно голодать под нагрузкой.
 * Явная проверка на старте превращает это в понятную ошибку конфигурации.
 */
@Component
@RequiredArgsConstructor
public class HikariPoolSizeValidator {

    private final AppProperties properties;

    @Value("${spring.datasource.hikari.maximum-pool-size}")
    private int hikariMaxPoolSize;

    @PostConstruct
    void validate() {
        int workerPoolSize = properties.worker().poolSize();
        if (hikariMaxPoolSize <= workerPoolSize) {
            throw new IllegalStateException(
                    "spring.datasource.hikari.maximum-pool-size (%d) должен быть больше app.worker.pool-size (%d): "
                            .formatted(hikariMaxPoolSize, workerPoolSize)
                            + "иначе воркерам, планировщику и REST-слою не хватит соединений");
        }
    }
}
