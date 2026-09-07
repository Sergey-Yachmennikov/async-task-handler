package com.erevan.async_task_handler.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * База для тестов, которым нужна настоящая PostgreSQL: поведение SKIP LOCKED,
 * CHECK-ограничения и частичные индексы на H2 не воспроизводятся.
 * <p>
 * Контейнер поднимается один раз на весь прогон (singleton-паттерн: запуск
 * в статическом блоке, без {@code @Testcontainers}). Аннотация управляла бы
 * жизненным циклом поштучно и перезапускала контейнер на каждый тестовый
 * класс; остановку здесь берёт на себя Ryuk при завершении JVM.
 * <p>
 * {@code @ServiceConnection} сам подставляет url/логин/пароль контейнера
 * в datasource — вручную переопределять свойства не требуется.
 */
/*
 * Планировщик задач по умолчанию выключен для всех интеграционных тестов.
 *
 * Spring кэширует контексты и не закрывает их до конца прогона, поэтому
 * включённый планировщик продолжал бы опрашивать общую БД и после того,
 * как его тестовый класс отработал: он разбирал бы задачи, созданные
 * соседними тестами, и конкурировал с их очисткой данных.
 *
 * Тесты, которым выполнение задач нужно, включают его сами — и закрывают
 * свой контекст через @DirtiesContext, чтобы воркеры не пережили класс.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.worker.enabled=false",
        // По той же причине выключено и восстановление зависших задач:
        // его планировщик из чужого контекста переводил бы в NEW строки,
        // которые соседний тест намеренно оставил в IN_PROGRESS
        "app.recovery.enabled=false"
})
public abstract class AbstractIntegrationTest {

    // В Testcontainers 2.x классы контейнеров перестали быть параметризованными:
    // self-type generic убран, поэтому пишется без <?>
    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine");

    /*
     * Kafka поднимается своя, а не берётся из docker-compose: тесты не должны
     * зависеть от того, что разработчик запустил инфраструктуру руками,
     * и не должны писать сообщения в очередь, с которой он работает.
     * org.testcontainers.kafka.KafkaContainer — вариант на KRaft, без ZooKeeper.
     */
    @ServiceConnection
    protected static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.2.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }
}
