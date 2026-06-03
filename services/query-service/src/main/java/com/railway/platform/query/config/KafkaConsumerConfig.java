package com.railway.platform.query.config;

import com.railway.platform.events.DelayPredictionEvent;
import com.railway.platform.events.ScheduleComputedEvent;
import com.railway.platform.events.TimetableChangedEvent;
import com.railway.platform.events.Topics;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
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
 * Kafka consumer configuration for the query-service.
 *
 * <p>The query-service is a pure consumer — it reads from TIMETABLE_CHANGED and
 * SCHEDULE_COMPUTED topics and projects events into the read model. A DLQ-only
 * (non-transactional) producer is configured solely to route unprocessable messages
 * to the dead-letter queue. DLQ writes do not require exactly-once semantics, so
 * no transactional-id is set on this producer.
 *
 * <p>Isolation level {@code read_committed} ensures projectors only see committed events
 * (not events from aborted schedule-service or timetable-service transactions).
 */
@Configuration
public class KafkaConsumerConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

  // TODO(config): Obtain from Vault path secret/query-service/kafka
  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  // TODO(config): Obtain from Vault path secret/query-service/schema-registry
  @Value("${spring.kafka.properties.schema.registry.url}")
  private String schemaRegistryUrl;

  @Value("${spring.kafka.consumer.group-id}")
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

  // ── Consumer: TimetableChangedEvent ─────────────────────────────────────────

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, TimetableChangedEvent>
      timetableChangedListenerContainerFactory() {

    var factory = new ConcurrentKafkaListenerContainerFactory<String, TimetableChangedEvent>();
    factory.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(baseConsumerProps(), new StringDeserializer(),
            new KafkaAvroDeserializer()));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(errorHandler());
    factory.setConcurrency(3);
    return factory;
  }

  // ── Consumer: ScheduleComputedEvent ─────────────────────────────────────────

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, ScheduleComputedEvent>
      scheduleComputedListenerContainerFactory() {

    var factory = new ConcurrentKafkaListenerContainerFactory<String, ScheduleComputedEvent>();
    factory.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(baseConsumerProps(), new StringDeserializer(),
            new KafkaAvroDeserializer()));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(errorHandler());
    factory.setConcurrency(3);
    return factory;
  }

  // ── Consumer: DelayPredictionEvent ──────────────────────────────────────────

  /**
   * Listener container factory for {@code railway.delay.prediction.computed}.
   *
   * <p>Routes unprocessable events to {@link Topics#DELAY_PREDICTION_DLQ} (a dedicated DLQ
   * separate from the main {@code railway.dlq}) to allow targeted triage and retention.
   * Retries use {@link ExponentialBackOff} with 100 ms base, factor 2.0, capped at 3 attempts.
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, DelayPredictionEvent>
      delayPredictionListenerContainerFactory() {

    var factory = new ConcurrentKafkaListenerContainerFactory<String, DelayPredictionEvent>();
    factory.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(baseConsumerProps(), new StringDeserializer(),
            new KafkaAvroDeserializer()));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(delayPredictionErrorHandler());
    factory.setConcurrency(3);
    return factory;
  }

  @Bean
  public DefaultErrorHandler delayPredictionErrorHandler() {
    var backOff = new ExponentialBackOff(100L, 2.0);
    backOff.setMaxAttempts(3);

    var recoverer = new DeadLetterPublishingRecoverer(dlqKafkaTemplate(),
        (record, ex) -> {
          log.error(
              "Sending DelayPredictionEvent to DLQ [topic={}] [partition={}] [offset={}] [error={}]",
              record.topic(), record.partition(), record.offset(), ex.getMessage());
          return new org.apache.kafka.common.TopicPartition(Topics.DELAY_PREDICTION_DLQ, -1);
        });

    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        org.apache.kafka.common.errors.SerializationException.class,
        IllegalArgumentException.class);
    return handler;
  }

  // ── Error handling ───────────────────────────────────────────────────────────

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

    handler.addNotRetryableExceptions(
        org.apache.kafka.common.errors.SerializationException.class,
        IllegalArgumentException.class);

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
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30_000);
    props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10_000);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
    return props;
  }
}
