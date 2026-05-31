package com.railway.platform.schedule.config;

import com.railway.platform.events.MaintenanceWindowEvent;
import com.railway.platform.events.TimetableChangedEvent;
import com.railway.platform.events.Topics;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer and producer configuration for the schedule-service.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Manual acknowledgement mode — offset committed only after successful DB write + Kafka
 *       publish, all within a single KafkaTransactionManager transaction.</li>
 *   <li>Exactly-once semantics: transactional producer (transaction-id-prefix) + consumer
 *       isolation.level=read_committed.</li>
 *   <li>Retry policy: 3 retries with exponential back-off (1s → 2s → 4s max 10s) before DLQ.</li>
 *   <li>Non-retryable exceptions (DeserializationException, IllegalArgumentException) bypass
 *       retries and route directly to DLQ.</li>
 *   <li>Separate container factories per event type allow different deserializer/concurrency
 *       settings without sharing configuration.</li>
 * </ul>
 */
@Configuration
public class KafkaConsumerConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

  // TODO(config): Obtain from Vault path secret/schedule-service/kafka
  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  // TODO(config): Obtain from Vault path secret/schedule-service/schema-registry
  @Value("${spring.kafka.properties.schema.registry.url}")
  private String schemaRegistryUrl;

  @Value("${spring.kafka.consumer.group-id}")
  private String groupId;

  @Value("${spring.kafka.producer.transaction-id-prefix}")
  private String transactionIdPrefix;

  // ── Producer (for DLQ + ScheduleComputedEvent) ──────────────────────────────

  @Bean
  public ProducerFactory<String, Object> producerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
    props.put("schema.registry.url", schemaRegistryUrl);
    props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionIdPrefix);
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.RETRIES_CONFIG, 10);
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
    props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);
    props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, Object> kafkaTemplate() {
    return new KafkaTemplate<>(producerFactory());
  }

  @Bean
  public KafkaTransactionManager<String, Object> kafkaTransactionManager() {
    return new KafkaTransactionManager<>(producerFactory());
  }

  // ── Consumer: TimetableChangedEvent ─────────────────────────────────────────

  @Bean
  public ConsumerFactory<String, TimetableChangedEvent> timetableChangedConsumerFactory() {
    Map<String, Object> props = baseConsumerProps();
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
        new KafkaAvroDeserializer());
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, TimetableChangedEvent>
      timetableChangedListenerContainerFactory() {

    var factory = new ConcurrentKafkaListenerContainerFactory<String, TimetableChangedEvent>();
    factory.setConsumerFactory(timetableChangedConsumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setTransactionManager(kafkaTransactionManager());
    factory.setCommonErrorHandler(errorHandler());
    factory.setConcurrency(3);
    return factory;
  }

  // ── Consumer: MaintenanceWindowEvent ────────────────────────────────────────

  @Bean
  public ConsumerFactory<String, MaintenanceWindowEvent> maintenanceWindowConsumerFactory() {
    Map<String, Object> props = baseConsumerProps();
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
        new KafkaAvroDeserializer());
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, MaintenanceWindowEvent>
      maintenanceWindowListenerContainerFactory() {

    var factory = new ConcurrentKafkaListenerContainerFactory<String, MaintenanceWindowEvent>();
    factory.setConsumerFactory(maintenanceWindowConsumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setTransactionManager(kafkaTransactionManager());
    factory.setCommonErrorHandler(errorHandler());
    factory.setConcurrency(3);
    return factory;
  }

  // ── Error handling ───────────────────────────────────────────────────────────

  /**
   * Retry 3 times with exponential back-off, then publish to DLQ.
   *
   * <p>Non-retryable exceptions (deserialization failures, illegal arguments) are routed to the
   * DLQ immediately without retrying, preventing poison-message livelock.
   */
  @Bean
  public DefaultErrorHandler errorHandler() {
    var backOff = new ExponentialBackOff(1_000L, 2.0);
    backOff.setMaxAttempts(3);
    backOff.setMaxInterval(10_000L);

    var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate(),
        (record, ex) -> {
          log.error("Sending record to DLQ [topic={}] [partition={}] [offset={}] [error={}]",
              record.topic(), record.partition(), record.offset(), ex.getMessage());
          return new org.apache.kafka.common.TopicPartition(Topics.DLQ, -1);
        });

    var handler = new DefaultErrorHandler(recoverer, backOff);

    // Non-retryable — route to DLQ immediately.
    handler.addNotRetryableExceptions(
        org.apache.kafka.common.errors.SerializationException.class,
        IllegalArgumentException.class,
        IllegalStateException.class);

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
