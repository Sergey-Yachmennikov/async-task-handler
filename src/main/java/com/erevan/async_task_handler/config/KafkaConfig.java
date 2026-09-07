package com.erevan.async_task_handler.config;

import com.erevan.async_task_handler.exception.InvalidTaskMessageException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Настройки Kafka поверх автоконфигурации Spring Boot.
 * <p>
 * Адреса брокеров, группа и десериализаторы заданы декларативно
 * в application.yaml — здесь только то, что требует кода.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    /**
     * Топик создаётся приложением, а не вручную: несколько партиций нужны,
     * чтобы инстансы сервиса могли читать очередь параллельно.
     */
    @Bean
    public NewTopic tasksTopic(AppProperties properties) {
        return TopicBuilder.name(properties.kafka().topic())
                .partitions(properties.kafka().partitions())
                .replicas(1)
                .build();
    }

    /**
     * Топик для необработанных сообщений.
     * <p>
     * Партиций столько же, сколько в основном: получатель кладёт сообщение
     * в партицию с тем же номером, и при меньшем их количестве публикация
     * в DLT падала бы для старших партиций.
     */
    @Bean
    public NewTopic tasksDltTopic(AppProperties properties) {
        return TopicBuilder.name(properties.kafka().dltTopic())
                .partitions(properties.kafka().partitions())
                .replicas(1)
                .build();
    }

    /**
     * Основной шаблон с сериализацией в JSON.
     * <p>
     * Объявлен явно, хотя Spring Boot создаёт такой же сам. Причина в том, что
     * его автоконфигурация помечена {@code @ConditionalOnMissingBean(KafkaTemplate.class)}:
     * стоит объявить в приложении любой другой KafkaTemplate — а ниже как раз
     * появляется шаблон для байтов — и Boot отступает, переставая создавать
     * основной. Условие смотрит на тип целиком и различать разные параметризации
     * не умеет.
     */
    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<?, ?> producerFactory) {
        // Приведение безопасно: фабрика к типам безразлична, за преобразование
        // отвечают сериализаторы, заданные в конфигурации по имени класса
        return new KafkaTemplate<>((ProducerFactory<String, Object>) producerFactory);
    }

    /**
     * Отдельный producer с сериализацией в байты — нужен получателю DLT.
     * <p>
     * Когда сообщение не удалось десериализовать, его значение остаётся сырым
     * массивом байт. Основной producer настроен на JSON и такое значение
     * записал бы искажённым, поэтому для этого случая используется свой.
     */
    @Bean
    public KafkaTemplate<String, byte[]> byteArrayKafkaTemplate(ProducerFactory<?, ?> producerFactory) {
        Map<String, Object> configs = new HashMap<>(producerFactory.getConfigurationProperties());
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(configs));
    }

    /**
     * Обработчик ошибок консьюмера.
     * <p>
     * Две попытки с паузой в секунду — этого достаточно для временных сбоев
     * вроде обрыва соединения с БД. Исчерпав их, сообщение уходит в DLT
     * и снимается с очереди: иначе оно встанет в голове партиции
     * и заблокирует всё, что за ним.
     * <p>
     * Ошибки валидации и десериализации в повторах не участвуют вовсе —
     * тело сообщения от повторной доставки не изменится, так что попытки
     * были бы потраченным временем.
     */
    // Имена параметров совпадают с именами бинов намеренно: после добавления
    // второго KafkaTemplate по одному лишь типу они неразличимы, и Spring
    // разрешает неоднозначность по имени
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate,
                                                 KafkaTemplate<String, byte[]> byteArrayKafkaTemplate,
                                                 AppProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                Map.of(
                        byte[].class, byteArrayKafkaTemplate,
                        Object.class, kafkaTemplate),
                // Имя топика берётся из конфигурации, а не собирается по умолчанию
                // добавлением суффикса .DLT — так оно задано в одном месте
                (failed, exception) -> {
                    log.warn("Сообщение отправляется в {}: partition={}, offset={}, key={}, причина={}",
                            properties.kafka().dltTopic(), failed.partition(), failed.offset(),
                            failed.key(), exception.getMessage());
                    return new TopicPartition(properties.kafka().dltTopic(), failed.partition());
                });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2L));
        handler.addNotRetryableExceptions(InvalidTaskMessageException.class);
        return handler;
    }
}
