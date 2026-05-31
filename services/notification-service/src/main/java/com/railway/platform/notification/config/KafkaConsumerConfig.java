package com.railway.platform.notification.config;

import com.railway.platform.events.NotificationRequestEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.LoggingErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration for the notification-service.
 *
 * <p>This service is a pure consumer — it does not produce any Kafka events. There is therefore no
 * KafkaTransactionManager or producer factory here. Idempotency is enforced at the DB layer via
 * the {@code processed_events} unique constraint rather than via Kafka transactions.
 *
 * <p>Key choices:
 * <ul>
 *   <li>Manual ack: offset committed only after DB write succeeds.</li>
 *   <li>read_committed: ignores in-flight transactional messages from upstream producers.</li>
 *   <li>3 retries with exponential back-off (1s → 2s → 4s, max 10s) before logging and discarding.
 *       No DLQ producer is wired here — a separate DLQ forwarder can be added in Phase 5.</li>
 *   <li>Non-retryable: SerializationException and IllegalArgumentException bypass retries.</li>
 * </ul>
 */
@Configuration
public class KafkaConsumerConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

  // TODO(config): Vault path secret/notification-service/kafka → bootstrap-servers
  @Value("${spring.kafka.bootstrap-servers:PLACEHOLDER_MSK_BROKER_1:9092}")
  private String bootstrapServers;

  // TODO(config): Vault path secret/notification-service/schema-registry → url
  @Value("${spring.kafka.properties.schema.registry.url:http://PLACEHOLDER_SCHEMA_REGISTRY:8081}")
  private String schemaRegistryUrl;

  @Value("${spring.kafka.consumer.group-id:notification-service-group}")
  private String groupId;

  @Bean
  public ConsumerFactory<String, NotificationRequestEvent> notificationRequestConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put("schema.registry.url", schemaRegistryUrl);
    // Deserializes into the concrete generated class rather than GenericRecord.
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
        new KafkaAvroDeserializer());
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, NotificationRequestEvent>
      notificationRequestListenerContainerFactory() {

    var factory =
        new ConcurrentKafkaListenerContainerFactory<String, NotificationRequestEvent>();
    factory.setConsumerFactory(notificationRequestConsumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(errorHandler());
    return factory;
  }

  /**
   * Retries 3 times with exponential back-off before logging and discarding the record.
   *
   * <p>No DLQ producer is configured because this service has no outbound Kafka producer.
   * A logging recoverer is used so that poison messages are visible in logs and metrics
   * without causing consumer livelock. TODO(phase-5): wire a dedicated DLQ producer.
   */
  @Bean
  public DefaultErrorHandler errorHandler() {
    var backOff = new ExponentialBackOff(1_000L, 2.0);
    backOff.setMaxAttempts(3);
    backOff.setMaxInterval(10_000L);

    var handler = new DefaultErrorHandler(
        (record, ex) -> log.error(
            "Record exhausted all retries and will be discarded "
                + "[topic={}] [partition={}] [offset={}] [error={}]",
            record.topic(), record.partition(), record.offset(), ex.getMessage(), ex),
        backOff);

    handler.addNotRetryableExceptions(SerializationException.class, IllegalArgumentException.class);

    return handler;
  }
}
