package com.railway.platform.distribution.producer;

import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.DistributionChannel;
import com.railway.platform.events.DistributionEvent;
import com.railway.platform.events.DistributionStatus;
import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes DistributionEvent records to {@code railway.distribution.events} within the
 * caller's Kafka transaction. Must only be called from a method annotated with
 * {@code @Transactional("kafkaTransactionManager")}.
 *
 * <p>timetableId is used as the message key so all tracking events for a given timetable
 * land on the same partition, preserving ordering for the saga state machine.
 *
 * <p>A Resilience4j circuit breaker wraps the Kafka send call to prevent cascading failures
 * when the Kafka cluster is unavailable or slow.
 */
@Component
public class DistributionEventProducer {

  private static final Logger log = LoggerFactory.getLogger(DistributionEventProducer.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

  public DistributionEventProducer(KafkaTemplate<String, Object> kafkaTemplate,
      CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
    this.kafkaTemplate = kafkaTemplate;
    this.circuitBreakerFactory = circuitBreakerFactory;
  }

  public void publish(
      String scheduleComputedEventId,
      String timetableId,
      DistributionChannel channel,
      DistributionStatus status,
      String failureReason,
      boolean isEmergency,
      String correlationId) {

    var metadata = EventMetadata.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setEventType(DistributionEvent.class.getName())
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId(correlationId)
        .setActor("system:distribution-service")
        .setSchemaVersion(1)
        .build();

    var event = DistributionEvent.newBuilder()
        .setMetadata(metadata)
        .setScheduleComputedEventId(scheduleComputedEventId)
        .setTimetableId(timetableId)
        .setChannel(channel)
        .setStatus(status)
        .setFailureReason(failureReason)
        .setIsEmergency(isEmergency)
        .build();

    var record = new ProducerRecord<String, Object>(
        Topics.DISTRIBUTION_EVENTS,
        null,
        timetableId,
        event);

    KafkaCorrelationIdPropagator.injectIntoHeaders(record.headers());

    CircuitBreaker cb = circuitBreakerFactory.create("kafka-distribution-producer");
    cb.run(() -> { kafkaTemplate.send(record); return null; },
        throwable -> {
          log.error("Kafka producer circuit open [producer=distribution] [error={}]",
              throwable.getMessage());
          throw new KafkaProducerCircuitOpenException("Distribution Kafka circuit open", throwable);
        });

    log.info("Published DistributionEvent [timetableId={}] [channel={}] [status={}] [correlationId={}]",
        timetableId, channel, status, correlationId);
  }

  /** Thrown when the Kafka producer circuit breaker is open for the distribution producer. */
  static class KafkaProducerCircuitOpenException extends RuntimeException {
    KafkaProducerCircuitOpenException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
