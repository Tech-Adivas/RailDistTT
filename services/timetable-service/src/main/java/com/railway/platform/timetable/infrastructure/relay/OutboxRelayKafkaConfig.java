package com.railway.platform.timetable.infrastructure.relay;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer configuration for the outbox fallback relay.
 *
 * <p>This producer is intentionally non-transactional. The relay is a fallback mechanism;
 * it relies on consumer idempotency (processed_events.event_id UNIQUE constraint) to handle
 * duplicates when both Debezium and the relay publish the same outbox event.
 */
@Configuration
public class OutboxRelayKafkaConfig {

    // TODO(config): Same Kafka bootstrap servers as application.yml
    @Value("${spring.kafka.bootstrap-servers:PLACEHOLDER_MSK_BROKER_1:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url:http://PLACEHOLDER_SCHEMA_REGISTRY:8081}")
    private String schemaRegistryUrl;

    @Bean("relayProducerFactory")
    public ProducerFactory<String, Object> relayProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put("schema.registry.url", schemaRegistryUrl);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 10);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean("relayKafkaTemplate")
    public KafkaTemplate<String, Object> relayKafkaTemplate() {
        return new KafkaTemplate<>(relayProducerFactory());
    }
}
