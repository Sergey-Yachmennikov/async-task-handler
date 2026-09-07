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
            summary = "Поставить задачу в очередь",
            description = """
                    Публикует задачу в Kafka и сразу отвечает, не дожидаясь выполнения.
                    Запись в БД создаёт консьюмер, поэтому идентификатор задачи
                    в ответе отсутствует.""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Задача принята в обработку"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации запроса",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<TaskAcceptedDto> submitTask(@Valid TaskRequestDto request);
}
