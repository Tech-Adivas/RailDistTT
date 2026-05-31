package com.railway.platform.notification.config;

import com.railway.platform.events.NotificationRequestEvent;
import com.railway.platform.events.Topics;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration for the notification-service.
 *
 * <p>A DLQ-only (non-transactional) producer is configured to route unprocessable
 * messages to the dead-letter queue. DLQ writes do not require exactly-once semantics,
 * so no transactional-id is set on this producer. Idempotency for notification delivery
 * is enforced at the DB layer via the {@code processed_events} unique constraint.
 *
 * <p>Key choices:
 * <ul>
 *   <li>Manual ack: offset committed only after DB write succeeds.</li>
 *   <li>read_committed: ignores in-flight transactional messages from upstream producers.</li>
 *   <li>3 retries with exponential back-off (1s → 2s → 4s, max 10s) before publishing to DLQ.</li>
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

  // ── DLQ Producer (non-transactional) ────────────────────────────────────────

  /**
   * Non-transactional producer factory used exclusively for writing to the DLQ.
   *
   * <p>No transactional-id is set: DLQ writes are best-effort and do not need
   * exactly-once guarantees. {@code enable.idempotence=true} combined with
   * {@code acks=all} and {@code retries=10} ensures at-least-once delivery to the DLQ.
   */
  @Bean
  public ProducerFactory<String, Object> dlqProducerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
    props.put("schema.registry.url", schemaRegistryUrl);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.RETRIES_CONFIG, 10);
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, Object> dlqKafkaTemplate() {
    return new KafkaTemplate<>(dlqProducerFactory());
  }

  // ── Consumer ─────────────────────────────────────────────────────────────────

  @Bean
  public ConsumerFactory<String, NotificationRequestEvent> notificationRequestConsumerFactory() {
    Map<String, Object> props = baseConsumerProps();
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
    factory.setConcurrency(3);
    return factory;
  }

  // ── Error handling ───────────────────────────────────────────────────────────

  /**
   * Retries 3 times with exponential back-off, then publishes the record to the DLQ.
   *
   * <p>Non-retryable exceptions (SerializationException, IllegalArgumentException) are
   * routed to the DLQ immediately without retrying, preventing poison-message livelock.
   */
  @Bean
  public DefaultErrorHandler errorHandler() {
    var backOff = new ExponentialBackOff(1_000L, 2.0);
    backOff.setMaxAttempts(3);
    backOff.setMaxInterval(10_000L);

    var recoverer = new DeadLetterPublishingRecoverer(dlqKafkaTemplate(),
        (record, ex) -> {
          log.error("Sending record to DLQ [topic={}] [partition={}] [offset={}] [error={}]",
              record.topic(), record.partition(), record.offset(), ex.getMessage());
          return new TopicPartition(Topics.DLQ, -1);
        });

    var handler = new DefaultErrorHandler(recoverer, backOff);

    handler.addNotRetryableExceptions(SerializationException.class, IllegalArgumentException.class);

    return handler;
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private Map<String, Object> baseConsumerProps() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put("schema.registry.url", schemaRegistryUrl);
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30_000);
    props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10_000);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
    return props;
  }
}
