package com.erevan.async_task_handler.config;

import com.erevan.async_task_handler.domain.TaskConstraints;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка настроек приложения.
 * <p>
 * Все проверки идут через корневой {@link AppProperties}, а не через вложенные
 * записи по отдельности — и это принципиально. Bean Validation не спускается
 * во вложенные объекты без {@code @Valid} на поле, поэтому тест, валидирующий
 * вложенную запись напрямую, проходит даже когда каскад не настроен
 * и в приложении не проверяется ровно ничего.
 */
class AppPropertiesValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("Конфигурация по умолчанию из application.yaml проходит проверку")
    void defaultConfigurationIsValid() {
        assertThat(validator.validate(properties(900_000, 8))).isEmpty();
    }

    @Test
    @DisplayName("Порог зависания ниже предельной длительности задачи отклоняется")
    void thresholdBelowMaxTaskDurationIsRejected() {
        assertThat(validator.validate(properties(60_000, 8)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .contains("stuck-timeout-ms"));
    }

    @Test
    @DisplayName("Порог, равный предельной длительности, тоже отклоняется")
    void thresholdEqualToMaxTaskDurationIsRejected() {
        // На самой границе задача ещё может выполняться, поэтому нужно строгое превышение
        assertThat(validator.validate(properties(TaskConstraints.MAX_DURATION_MS, 8))).hasSize(1);
    }

    @Test
    @DisplayName("Нулевой размер пула отклоняется")
    void zeroPoolSizeIsRejected() {
        /*
         * Без каскада это нарушение проходило бы незамеченным, а сервис
         * поднимался бы полностью работоспособным на вид: консьюмер читает
         * Kafka, задачи копятся в статусе NEW, и ни одна из них не выполняется.
         */
        assertThat(validator.validate(properties(900_000, 0)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getPropertyPath())
                        .hasToString("worker.poolSize"));
    }

    @Test
    @DisplayName("Пустое имя топика отклоняется")
    void blankTopicIsRejected() {
        AppProperties withBlankTopic = new AppProperties(
                new AppProperties.Kafka("", "tasks.DLT", 3),
                new AppProperties.Worker(true, 8, 1_000, 500),
                new AppProperties.Recovery(true, 900_000, 30_000, 3, 50));

        assertThat(validator.validate(withBlankTopic))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getPropertyPath())
                        .hasToString("kafka.topic"));
    }

    private AppProperties properties(long stuckTimeoutMs, int poolSize) {
        return new AppProperties(
                new AppProperties.Kafka("tasks", "tasks.DLT", 3),
                new AppProperties.Worker(true, poolSize, 1_000, 500),
                new AppProperties.Recovery(true, stuckTimeoutMs, 30_000, 3, 50));
    }
}
