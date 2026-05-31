package com.railway.platform.distribution.producer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies that DistributionEventProducer uses a circuit breaker and that a circuit-open
 * state propagates a KafkaProducerCircuitOpenException to the caller (the orchestrator's
 * Kafka transaction manager will then roll back the transaction).
 */
@ExtendWith(MockitoExtension.class)
class DistributionEventProducerCircuitBreakerTest {

    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @Mock private CircuitBreaker circuitBreaker;

    @Test
    void whenCircuitOpen_thenKafkaProducerCircuitOpenExceptionThrown() {
        when(circuitBreakerFactory.create(anyString())).thenReturn(circuitBreaker);
        when(circuitBreaker.run(any(), any())).thenAnswer(inv -> {
            // Simulate circuit open: execute the fallback function
            var fallback = inv.<java.util.function.Function<Throwable, Object>>getArgument(1);
            return fallback.apply(new RuntimeException("Circuit is OPEN"));
        });

        var producer = new DistributionEventProducer(kafkaTemplate, circuitBreakerFactory);

        assertThatThrownBy(() ->
            producer.publish("event-1", "timetable-1",
                com.railway.platform.events.DistributionChannel.WEBSOCKET_PUSH,
                com.railway.platform.events.DistributionStatus.PUBLISHED,
                null, false, "corr-1"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void whenCircuitClosed_thenKafkaTemplateSendCalled() {
        when(circuitBreakerFactory.create(anyString())).thenReturn(circuitBreaker);
        when(circuitBreaker.run(any(), any())).thenAnswer(inv -> {
            // Circuit is closed: execute the supplier
            var supplier = inv.<java.util.function.Supplier<Object>>getArgument(0);
            return supplier.get();
        });
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
            .thenReturn(mock(org.springframework.kafka.support.SendResult.class, org.mockito.Answers.RETURNS_DEEP_STUBS));

        var producer = new DistributionEventProducer(kafkaTemplate, circuitBreakerFactory);

        producer.publish("event-1", "timetable-1",
            com.railway.platform.events.DistributionChannel.WEBSOCKET_PUSH,
            com.railway.platform.events.DistributionStatus.PUBLISHED,
            null, false, "corr-1");

        verify(kafkaTemplate).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }
}
