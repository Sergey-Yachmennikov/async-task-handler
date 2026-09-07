package com.erevan.async_task_handler.controller;

import com.erevan.async_task_handler.dto.ErrorResponse;
import com.erevan.async_task_handler.dto.TaskAcceptedDto;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.dto.TaskResponseDto;
import com.erevan.async_task_handler.kafka.TaskProducer;
import com.erevan.async_task_handler.service.TaskQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
/*
 * @Validated здесь намеренно не стоит. Начиная со Spring Framework 6.1
 * проверка ограничений на параметрах контроллера встроена и включается сама
 * при наличии таких аннотаций, как @Positive ниже. Аннотация @Validated
 * переключила бы механизм на старый, через AOP-прокси, с другим типом
 * исключения — то есть добавила бы прокси на ровном месте.
 */
@Tag(name = "Tasks", description = "Постановка задач и просмотр результатов выполнения")
public class TaskController {

    private final TaskQueryService taskQueryService;
    private final TaskProducer taskProducer;

    @GetMapping("/{id}")
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
    public TaskResponseDto getTask(
            @Parameter(description = "Идентификатор задачи", example = "1")
            @PathVariable @Positive(message = "Идентификатор задачи должен быть положительным") Long id) {
        return taskQueryService.findById(id);
    }

    @PostMapping
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
    public ResponseEntity<TaskAcceptedDto> submitTask(@Valid @RequestBody TaskRequestDto request) {
        String correlationKey = taskProducer.send(request);
        // 202, а не 201: ресурс на момент ответа ещё не создан
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new TaskAcceptedDto("Задача принята в обработку", correlationKey));
    }
}
