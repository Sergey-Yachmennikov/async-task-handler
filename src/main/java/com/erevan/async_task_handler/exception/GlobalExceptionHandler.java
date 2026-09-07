package com.erevan.async_task_handler.exception;

import com.erevan.async_task_handler.dto.ErrorResponse;
import com.erevan.async_task_handler.dto.ErrorResponse.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Единая обработка ошибок API, п.4 дополнительных требований ТЗ.
 * <p>
 * Каждому классу ошибок сопоставлен свой HTTP-статус, а тело ответа во всех
 * случаях имеет одинаковую форму — клиенту не приходится разбирать разные
 * форматы в зависимости от того, что именно пошло не так.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTaskNotFound(TaskNotFoundException e,
                                                            HttpServletRequest request) {
        // Обычная ситуация, а не сбой: пишем на уровне debug, чтобы опрос
        // несуществующих задач не засорял журнал ошибок
        log.debug("Задача не найдена: {}", e.getMessage());
        return build(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    /** Тело запроса не прошло валидацию: @Valid на @RequestBody. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        List<FieldViolation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        log.warn("Ошибка валидации тела запроса {}: {}", request.getRequestURI(), violations);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Запрос не прошёл валидацию",
                        request.getRequestURI(),
                        violations));
    }

    /**
     * Нарушены ограничения на параметрах метода контроллера: @Positive
     * на переменной пути. Именно этот тип бросает встроенная в Spring 6.1+
     * валидация параметров.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParameterValidation(HandlerMethodValidationException e,
                                                                   HttpServletRequest request) {
        // В Spring Framework 7 общий getAllValidationResults() разделён
        // на результаты по отдельным параметрам и межпараметрические
        List<FieldViolation> violations = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        log.warn("Ошибка валидации параметров {}: {}", request.getRequestURI(), violations);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Некорректные параметры запроса",
                        request.getRequestURI(),
                        violations));
    }

    /** Идентификатор в пути не приводится к числу: /api/tasks/abc. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        String message = "Параметр '%s' имеет недопустимое значение: %s".formatted(e.getName(), e.getValue());
        log.warn("{} на {}", message, request.getRequestURI());
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /** Тело запроса не является корректным JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e,
                                                              HttpServletRequest request) {
        log.warn("Нечитаемое тело запроса на {}: {}", request.getRequestURI(), e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Тело запроса не удалось разобрать", request);
    }

    /**
     * Всё непредусмотренное. Подробности исключения уходят в лог со стектрейсом,
     * а наружу отдаётся общая формулировка: детали внутреннего устройства
     * сервиса клиенту знать незачем.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Необработанная ошибка на {}", request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервиса", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), status.getReasonPhrase(),
                        message, request.getRequestURI()));
    }
}
