package com.erevan.async_task_handler.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Валидация входных данных проверяется без поднятия контекста Spring —
 * ограничения объявлены на самой записи и от контейнера не зависят.
 */
class TaskRequestDtoValidationTest {

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
    @DisplayName("Корректный запрос проходит без замечаний")
    void validRequestHasNoViolations() {
        assertThat(validator.validate(new TaskRequestDto("generate-report", 5_000L))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("Пустое или пробельное название отклоняется")
    void blankNameIsRejected(String name) {
        Set<ConstraintViolation<TaskRequestDto>> violations =
                validator.validate(new TaskRequestDto(name, 5_000L));

        assertThat(violations).singleElement()
                .satisfies(v -> {
                    assertThat(v.getPropertyPath()).hasToString("name");
                    assertThat(v.getMessage()).isEqualTo("Название задачи обязательно");
                });
    }

    @Test
    @DisplayName("Название длиннее 255 символов отклоняется")
    void tooLongNameIsRejected() {
        assertThat(validator.validate(new TaskRequestDto("x".repeat(256), 5_000L)))
                .singleElement()
                .satisfies(v -> assertThat(v.getPropertyPath()).hasToString("name"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -5_000L})
    @DisplayName("Неположительная длительность отклоняется")
    void nonPositiveDurationIsRejected(long durationMs) {
        assertThat(validator.validate(new TaskRequestDto("task", durationMs)))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.getPropertyPath()).hasToString("durationMs");
                    assertThat(v.getMessage()).isEqualTo("Длительность должна быть положительной");
                });
    }

    @Test
    @DisplayName("Отсутствие длительности отклоняется")
    void nullDurationIsRejected() {
        assertThat(validator.validate(new TaskRequestDto("task", null)))
                .singleElement()
                .satisfies(v -> assertThat(v.getMessage()).isEqualTo("Длительность обязательна"));
    }

    @Test
    @DisplayName("Длительность сверх допустимого предела отклоняется")
    void tooLongDurationIsRejected() {
        assertThat(validator.validate(new TaskRequestDto("task", 600_001L)))
                .singleElement()
                .satisfies(v -> assertThat(v.getPropertyPath()).hasToString("durationMs"));
    }
}
