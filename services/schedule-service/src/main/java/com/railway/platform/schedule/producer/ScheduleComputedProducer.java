package com.railway.platform.schedule.producer;

import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.ScheduleComputedEvent;
import com.railway.platform.events.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes ScheduleComputedEvents to Kafka with exactly-once semantics.
 *
 * <p>The KafkaTemplate is configured with a transactional producer (spring.kafka.producer
 * .transaction-id-prefix). This, combined with the consumer's isolation.level=read_committed,
 * ensures the read-process-write loop is exactly-once: if the producer transaction aborts,
 * the consumer offset is not committed either (handled by the consumer's executeInTransaction).
 *
 * <p>The timetableId is used as the Kafka message key so all schedule events for the same
 * timetable land on the same partition, preserving ordering.
 *
 * <p>A Resilience4j circuit breaker wraps the Kafka send call to prevent cascading failures
 * when the Kafka cluster is unavailable or slow.
 */
@Component
public class ScheduleComputedProducer {

  private static final Logger log = LoggerFactory.getLogger(ScheduleComputedProducer.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

  public ScheduleComputedProducer(KafkaTemplate<String, Object> kafkaTemplate,
      CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
    this.kafkaTemplate = kafkaTemplate;
    this.circuitBreakerFactory = circuitBreakerFactory;
  }

  /**
   * Publishes a ScheduleComputedEvent within the caller's Kafka transaction.
   * Must be called inside a transaction started by KafkaTransactionManager.
   *
   * @param event        The computed schedule event to publish.
   * @param correlationId Propagated as a Kafka record header for distributed tracing.
   */
  public void publish(ScheduleComputedEvent event, String correlationId) {
    var record = new ProducerRecord<String, Object>(
        Topics.SCHEDULE_COMPUTED,
        null,                            // partition — let Kafka assign based on key
        event.getTimetableId().toString(), // key — ensures ordering per timetable
        event);

    // Propagate correlation ID as a Kafka header so downstream consumers restore it into MDC.
    KafkaCorrelationIdPropagator.injectIntoHeaders(record.headers());

    CircuitBreaker cb = circuitBreakerFactory.create("kafka-schedule-producer");
    cb.run(() -> { kafkaTemplate.send(record); return null; },
        throwable -> {
          log.error("Kafka producer circuit open [producer=schedule] [error={}]",
              throwable.getMessage());
          throw new KafkaProducerCircuitOpenException("Schedule Kafka circuit open", throwable);
        });

    log.info("Published ScheduleComputedEvent [timetableId={}] [eventId={}] [correlationId={}]",
        event.getTimetableId(),
        event.getMetadata().getEventId(),
        correlationId);
  }

  /** Thrown when the Kafka producer circuit breaker is open for the schedule producer. */
  static class KafkaProducerCircuitOpenException extends RuntimeException {
    KafkaProducerCircuitOpenException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
