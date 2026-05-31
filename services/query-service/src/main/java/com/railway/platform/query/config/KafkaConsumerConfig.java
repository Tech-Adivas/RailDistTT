package com.railway.platform.query.config;

import com.railway.platform.events.ScheduleComputedEvent;
import com.railway.platform.events.TimetableChangedEvent;
import com.railway.platform.events.Topics;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
 * SCHEDULE_COMPUTED topics and projects events into the read model. It does not
 * produce any events, so there is no KafkaTransactionManager or producer config here.
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

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, TimetableChangedEvent>
      timetableChangedListenerContainerFactory() {

    var factory = new ConcurrentKafkaListenerContainerFactory<String, TimetableChangedEvent>();
    factory.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(baseConsumerProps(), new StringDeserializer(),
            new KafkaAvroDeserializer()));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(errorHandler());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, ScheduleComputedEvent>
      scheduleComputedListenerContainerFactory() {

    var factory = new ConcurrentKafkaListenerContainerFactory<String, ScheduleComputedEvent>();
    factory.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(baseConsumerProps(), new StringDeserializer(),
            new KafkaAvroDeserializer()));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(errorHandler());
    return factory;
  }

  @Bean
  public DefaultErrorHandler errorHandler() {
    var backOff = new ExponentialBackOff(1_000L, 2.0);
    backOff.setMaxAttempts(3);
    backOff.setMaxInterval(10_000L);

    // Query-service has no producer configured for DLQ, so use a logging recoverer.
    var handler = new DefaultErrorHandler(
        (record, ex) -> log.error(
            "Failed to project record after retries — dropping [topic={}] [partition={}] "
                + "[offset={}] [error={}]",
            record.topic(), record.partition(), record.offset(), ex.getMessage()),
        backOff);

    handler.addNotRetryableExceptions(
        org.apache.kafka.common.errors.SerializationException.class,
        IllegalArgumentException.class);

    return handler;
  }

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
    return props;
  }
}
