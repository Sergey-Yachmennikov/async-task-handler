package com.erevan.async_task_handler.controller;

import com.erevan.async_task_handler.dto.ErrorResponse;
import com.erevan.async_task_handler.dto.TaskAcceptedDto;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.dto.TaskResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;

/**
 * Контракт REST API задач: описание для документации отдельно от реализации.
 * <p>
 * Аннотации OpenAPI собраны здесь, а {@link TaskController} остаётся только
 * с привязкой к HTTP и вызовами сервисов. Интерфейс читается как спецификация
 * API целиком, не перемежаясь кодом.
 * <p>
 * Ограничения Bean Validation ({@code @Positive}, {@code @Valid}) объявлены
 * тоже здесь, и это не вопрос вкуса. Спецификация Bean Validation запрещает
 * усиливать ограничения параметров в реализующем методе: перенеси их в
 * {@code TaskController} — и валидатор бросит {@code ConstraintDeclarationException}
 * вместо проверки. Аннотации Spring MVC, напротив, остаются в реализации:
 * привязка параметров к запросу — её ответственность.
 */
@Tag(name = "Tasks", description = "Постановка задач и просмотр результатов выполнения")
public interface TaskApi {

    @Operation(
            summary = "Получить состояние задачи",
            description = "Возвращает текущий статус, прогресс и результат выполнения задачи")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Задача найдена"),
            @ApiResponse(responseCode = "400", description = "Некорректный идентификатор",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Задача не найдена",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    TaskResponseDto getTask(
            @Parameter(description = "Идентификатор задачи", example = "1")
            @Positive(message = "Идентификатор задачи должен быть положительным")
            Long id);

    @Operation(
            summary = "Найти задачу по ключу из ответа POST /api/tasks",
            description = """
                    На момент 202-ответа id задачи ещё не существует — в БД её создаёт
                    консьюмер. correlationKey из этого ответа позволяет найти задачу,
                    когда она появится.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Задача найдена"),
            @ApiResponse(responseCode = "404", description = "Задача с таким ключом не найдена "
                    + "(либо ключ неверный, либо консьюмер ещё не обработал сообщение)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    TaskResponseDto getTaskByCorrelationKey(
            @Parameter(description = "Ключ из ответа на POST /api/tasks", example = "3f2b9c1e-4a7d-4c3e-9f10-2b8e5d7a1c04")
            @NotBlank(message = "correlationKey обязателен")
            String correlationKey);

    @Operation(
            summary = "Поставить задачу в очередь",
            description = """
                    Публикует задачу в Kafka, дожидается подтверждения от брокера
                    и сразу отвечает, не дожидаясь выполнения. Запись в БД создаёт
                    консьюмер, поэтому идентификатор задачи в ответе отсутствует —
                    вместо него используется correlationKey, см. GET /api/tasks?correlationKey=.""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Задача принята в обработку"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации запроса",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Kafka не подтвердила запись сообщения",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<TaskAcceptedDto> submitTask(@Valid TaskRequestDto request);
}
