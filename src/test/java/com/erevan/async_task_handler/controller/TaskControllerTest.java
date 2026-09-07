package com.erevan.async_task_handler.controller;

import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.dto.TaskResponseDto;
import com.erevan.async_task_handler.exception.TaskNotFoundException;
import com.erevan.async_task_handler.kafka.TaskProducer;
import com.erevan.async_task_handler.service.TaskQueryService;
// Spring Boot 4 использует Jackson 3, у которого пакет tools.jackson.
// Jackson 2 тоже есть в classpath транзитивно, но бина ObjectMapper из него нет
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// В Spring Boot 4 срез веб-слоя переехал в модуль spring-boot-webmvc-test
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Контракт REST и соответствие HTTP-статусов ошибкам, пп. 2 и 4 ТЗ.
 * Слой веба тестируется изолированно: БД и Kafka заменены заглушками.
 */
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskQueryService taskQueryService;

    @MockitoBean
    private TaskProducer taskProducer;

    @Test
    @DisplayName("GET существующей задачи возвращает 200 и её состояние")
    void getExistingTaskReturnsOk() throws Exception {
        given(taskQueryService.findById(1L)).willReturn(new TaskResponseDto(
                1L, "report", 5_000L, TaskStatus.IN_PROGRESS, 40,
                null, null, "ath-node-1", Instant.now(), Instant.now(), null));

        mockMvc.perform(get("/api/tasks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("report"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.progress").value(40))
                .andExpect(jsonPath("$.workerId").value("ath-node-1"));
    }

    @Test
    @DisplayName("GET несуществующей задачи возвращает 404")
    void getMissingTaskReturnsNotFound() throws Exception {
        given(taskQueryService.findById(42L)).willThrow(new TaskNotFoundException(42L));

        mockMvc.perform(get("/api/tasks/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Задача с id 42 не найдена"))
                .andExpect(jsonPath("$.path").value("/api/tasks/42"));
    }

    @Test
    @DisplayName("GET с нечисловым идентификатором возвращает 400")
    void getWithNonNumericIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/tasks/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET с неположительным идентификатором возвращает 400")
    void getWithNonPositiveIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/tasks/-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("id"))
                .andExpect(jsonPath("$.violations[0].message")
                        .value("Идентификатор задачи должен быть положительным"));
    }

    @Test
    @DisplayName("POST валидной задачи возвращает 202 и публикует её в Kafka")
    void postValidTaskReturnsAccepted() throws Exception {
        given(taskProducer.send(any(TaskRequestDto.class))).willReturn("corr-key-1");

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TaskRequestDto("generate-report", 5_000L))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.correlationKey").value("corr-key-1"));

        verify(taskProducer).send(any(TaskRequestDto.class));
    }

    @Test
    @DisplayName("POST невалидной задачи возвращает 400 с перечнем нарушений")
    void postInvalidTaskReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskRequestDto("  ", -5L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.violations.length()").value(2));

        // Невалидный запрос не должен доходить до Kafka
        verify(taskProducer, never()).send(any());
    }

    @Test
    @DisplayName("POST с нечитаемым телом возвращает 400")
    void postMalformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ это не json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Тело запроса не удалось разобрать"));
    }
}
