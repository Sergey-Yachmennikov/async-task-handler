package com.erevan.async_task_handler.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Идентификатор инстанса сервиса, проставляемый в захваченные задачи.
 * <p>
 * По нему видно, как несколько экземпляров поделили очередь между собой —
 * это и есть наблюдаемое подтверждение горизонтального масштабирования (п.5 ТЗ).
 * В контейнере имя хоста совпадает с идентификатором контейнера, поэтому
 * инстансы различимы прямо в выдаче REST.
 */
@Slf4j
@Component
public class WorkerIdentity {

    private final String id;

    public WorkerIdentity() {
        this.id = resolveHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Идентификатор инстанса: {}", id);
    }

    public String id() {
        return id;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // Имя хоста — не критичный ресурс: случайного суффикса достаточно,
            // чтобы инстансы отличались друг от друга
            return "unknown-host";
        }
    }
}
