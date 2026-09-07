package com.erevan.async_task_handler.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Описание API для Swagger UI, п.3 дополнительных требований ТЗ.
 * Документация доступна на /swagger-ui.html, спецификация — на /v3/api-docs.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI asyncTaskHandlerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Async Task Handler API")
                .version("1.0")
                .description("""
                        Сервис распределённого асинхронного выполнения задач.

                        Задачи поступают из топика Kafka либо через POST /api/tasks,
                        сохраняются в PostgreSQL и выполняются пулом воркеров.
                        Несколько экземпляров сервиса работают с общей базой:
                        задачи захватываются через FOR UPDATE SKIP LOCKED,
                        что исключает их повторную обработку."""));
    }
}
