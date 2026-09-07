package com.erevan.async_task_handler.kafka;

import com.erevan.async_task_handler.domain.Task;
import com.erevan.async_task_handler.domain.TaskStatus;
import com.erevan.async_task_handler.dto.TaskRequestDto;
import com.erevan.async_task_handler.repository.TaskRepository;
import com.erevan.async_task_handler.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Путь «сообщение в Kafka → запись в БД» целиком, п.1 ТЗ, плюс идемпотентность
 * приёма и отправка неисправимых сообщений в отдельный топик.
 * <p>
 * Планировщик воркеров выключен базовым классом: здесь проверяется только
 * приём задач, а выполнение увело бы их из NEW до момента проверки.
 */
class TaskConsumerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private TaskRepository taskRepository;

    @Value("${app.kafka.topic}")
    private String topic;

    @Value("${app.kafka.dlt-topic}")
    private String dltTopic;

    @BeforeEach
    void clearTasks() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Валидное сообщение сохраняется задачей в статусе NEW")
    void validMessageIsStoredAsNewTask() {
        kafkaTemplate.send(topic, "key-1", new TaskRequestDto("generate-report", 1_500L));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    List<Task> tasks = taskRepository.findAll();
                    assertThat(tasks).singleElement().satisfies(task -> {
                        assertThat(task.getName()).isEqualTo("generate-report");
                        assertThat(task.getDurationMs()).isEqualTo(1_500L);
                        assertThat(task.getStatus()).isEqualTo(TaskStatus.NEW);
                        assertThat(task.getProgress()).isZero();
                        assertThat(task.getCreatedAt()).isNotNull();
                        // Ключ сообщения сохраняется — по нему отсекаются повторы
                        assertThat(task.getDedupKey()).isEqualTo("key-1");
                    });
                });
    }

    @Test
    @DisplayName("Повторная доставка того же сообщения не создаёт вторую задачу")
    void redeliveredMessageIsDeduplicated() {
        String sameKey = "duplicate-key";
        TaskRequestDto request = new TaskRequestDto("idempotent-task", 1_000L);

        kafkaTemplate.send(topic, sameKey, request);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(taskRepository.findAll()).hasSize(1));

        // Kafka гарантирует доставку «хотя бы один раз», поэтому то же самое
        // сообщение вполне может прийти повторно
        kafkaTemplate.send(topic, sameKey, request);
        // Следом отправляется другое сообщение: как только появилось оно,
        // повтор заведомо уже обработан, и число задач можно проверять
        kafkaTemplate.send(topic, "other-key", new TaskRequestDto("another-task", 1_000L));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(taskRepository.findAll())
                        .extracting(Task::getName)
                        .containsExactlyInAnyOrder("idempotent-task", "another-task"));
    }

    @Test
    @DisplayName("Невалидное сообщение уходит в DLT и не блокирует очередь")
    void invalidMessageGoesToDeadLetterTopic() {
        // Отрицательная длительность не пройдёт валидацию
        kafkaTemplate.send(topic, "bad-key", new TaskRequestDto("broken", -1L));
        // Отправляется следом: если бы битое сообщение вызывало бесконечные
        // повторы, это бы до обработки не дошло
        kafkaTemplate.send(topic, "good-key", new TaskRequestDto("valid-task", 1_000L));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(taskRepository.findAll())
                        .singleElement()
                        .satisfies(task -> assertThat(task.getName()).isEqualTo("valid-task")));

        assertThat(readDeadLetterKeys())
                .as("отбракованное сообщение должно сохраниться в %s", dltTopic)
                .contains("bad-key");
    }

    /** Вычитывает ключи всех сообщений, накопившихся в топике неисправимых. */
    private List<String> readDeadLetterKeys() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Значение читается байтами: в DLT попадают в том числе сообщения,
        // которые как раз и не удалось разобрать
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(dltTopic));
            List<String> keys = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(dltRecord -> keys.add(dltRecord.key()));
                assertThat(keys).isNotEmpty();
            });
            return keys;
        }
    }
}
