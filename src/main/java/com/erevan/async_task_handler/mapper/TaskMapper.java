package com.erevan.async_task_handler.mapper;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.dto.TaskResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Преобразования между сущностью и DTO.
 * <p>
 * Реализацию генерирует MapStruct на этапе компиляции: класс
 * {@code TaskMapperImpl} появляется в target/generated-sources и регистрируется
 * бином Spring благодаря {@code componentModel = SPRING}.
 * <p>
 * {@code unmappedTargetPolicy = ERROR} выбрана намеренно. При такой настройке
 * поле, добавленное в целевой тип и никак не заполненное, роняет сборку —
 * вместо того чтобы молча оставаться null и всплыть уже в бою. Обратная сторона:
 * каждое сознательно пропускаемое поле приходится перечислять явно, зато список
 * ниже читается как документация того, что заполняется не здесь.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TaskMapper {

    /**
     * Новая задача из входящего сообщения.
     * <p>
     * Игнорируемые поля заполняются не здесь: идентификатор и версию присваивает
     * БД, {@code createdAt} — Hibernate, остальное появляется по мере
     * выполнения задачи воркером. {@code dedupKey} проставляется отдельно
     * в {@code TaskRegistrationService}: он берётся из метаданных сообщения,
     * а не из его тела.
     */
    @Mapping(target = "status", constant = "NEW")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "progress", ignore = true)
    @Mapping(target = "result", ignore = true)
    @Mapping(target = "errorMessage", ignore = true)
    @Mapping(target = "workerId", ignore = true)
    @Mapping(target = "retryCount", ignore = true)
    @Mapping(target = "dedupKey", ignore = true)
    @Mapping(target = "startedAt", ignore = true)
    @Mapping(target = "finishedAt", ignore = true)
    Task toEntity(TaskRequestDto request);

    /**
     * Состояние задачи для REST.
     * <p>
     * Аннотаций нет: имена полей записи совпадают с именами полей сущности,
     * и MapStruct сопоставляет их сам. Поля {@code retryCount} и
     * {@code dedupKey} в ответ не попадают — это внутренняя механика,
     * наружу её отдавать незачем.
     */
    TaskResponseDto toResponse(Task task);
}
