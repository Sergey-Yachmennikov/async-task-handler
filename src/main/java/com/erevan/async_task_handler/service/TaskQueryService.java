package com.erevan.async_task_handler.service;

import com.erevan.async_task_handler.dto.TaskResponseDto;
import com.erevan.async_task_handler.exception.TaskNotFoundException;
import com.erevan.async_task_handler.mapper.TaskMapper;
import com.erevan.async_task_handler.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Чтение состояния задач для REST, п.2 ТЗ.
 */
@Service
@RequiredArgsConstructor
public class TaskQueryService {

    private final TaskRepository taskRepository;
    private final TaskMapper taskMapper;

    /**
     * @throws TaskNotFoundException если задачи с таким идентификатором нет
     */
    @Transactional(readOnly = true)
    public TaskResponseDto findById(Long id) {
        return taskRepository.findById(id)
                .map(taskMapper::toResponse)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    /**
     * Поиск по ключу, полученному клиентом в ответ на POST /api/tasks.
     * Так задачу можно найти, не зная её id — на момент постановки в Kafka
     * его ещё не существует.
     *
     * @throws TaskNotFoundException если задачи с таким ключом нет — либо
     *                                консьюмер ещё не обработал сообщение,
     *                                либо ключ ошибочный
     */
    @Transactional(readOnly = true)
    public TaskResponseDto findByCorrelationKey(String correlationKey) {
        return taskRepository.findByDedupKey(correlationKey)
                .map(taskMapper::toResponse)
                .orElseThrow(() -> new TaskNotFoundException(correlationKey));
    }
}
