package com.railway.platform.schedule.consumer;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.ScheduleComputedEvent;
import com.railway.platform.events.TimetableChangedEvent;
import com.railway.platform.events.Topics;
import com.railway.platform.schedule.infrastructure.persistence.entity.ComputedScheduleEntity;
import com.railway.platform.schedule.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.schedule.infrastructure.persistence.repository.ComputedScheduleRepository;
import com.railway.platform.schedule.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.schedule.producer.ScheduleComputedProducer;
import com.railway.platform.schedule.service.ScheduleComputationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Idempotent consumer for TimetableChangedEvent.
 *
 * <p>Processing guarantee: at-least-once delivery + idempotency guard on processed_events.event_id
 * unique constraint. Duplicate deliveries are safely detected and skipped.
 *
 * <p>Exactly-once output: the JPA writes (processed_events + computed_schedules) and the Kafka
 * publish are wrapped in a single KafkaTransactionManager transaction. If the Kafka send aborts,
 * the DB transaction is rolled back; if the DB write fails, the Kafka transaction is aborted.
 *
 * <p>Retry and DLQ: non-retryable failures (poison messages, schema mismatches) are routed
 * directly to the DLQ by the {@code DefaultErrorHandler} configured in {@link KafkaConsumerConfig}.
 * Transient failures (DB unavailable, timeouts) retry 3 times with exponential back-off before
 * the record is sent to the DLQ.
 */
@Component
public class TimetableChangedConsumer {

  private static final Logger log = LoggerFactory.getLogger(TimetableChangedConsumer.class);

  private final ScheduleComputationService computationService;
  private final ScheduleComputedProducer producer;
  private final ProcessedEventRepository processedEventRepository;
  private final ComputedScheduleRepository computedScheduleRepository;
  private final KafkaTemplate<String, Object> dlqTemplate;

  public TimetableChangedConsumer(
      ScheduleComputationService computationService,
      ScheduleComputedProducer producer,
      ProcessedEventRepository processedEventRepository,
      ComputedScheduleRepository computedScheduleRepository,
      KafkaTemplate<String, Object> dlqTemplate) {
    this.computationService = computationService;
    this.producer = producer;
    this.processedEventRepository = processedEventRepository;
    this.computedScheduleRepository = computedScheduleRepository;
    this.dlqTemplate = dlqTemplate;
  }

  /**
   * Processes a single TimetableChangedEvent.
   *
   * <p>The {@code containerFactory} is configured with manual ack mode and
   * {@code KafkaTransactionManager} so the offset commit and DB writes are atomic.
   */
  @KafkaListener(
      topics = Topics.TIMETABLE_CHANGED,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "timetableChangedListenerContainerFactory")
  @Transactional("kafkaTransactionManager")
  public void consume(ConsumerRecord<String, TimetableChangedEvent> record, Acknowledgment ack) {
    TimetableChangedEvent event = record.value();
    String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());

    CorrelationIdHolder.set(correlationId);

    try {
      String eventId = event.getMetadata().getEventId().toString();

      // Idempotency gate: skip if already processed.
      if (processedEventRepository.existsByEventId(eventId)) {
        log.info("Skipping duplicate event [eventId={}] [timetableId={}]",
            eventId, event.getTimetableId());
        ack.acknowledge();
        return;
      }

      var computed = computationService.compute(event, correlationId);

      // Persist idempotency record + computed schedule atomically in the same transaction.
      processedEventRepository.save(
          new ProcessedEventEntity(eventId, TimetableChangedEvent.class.getName()));
      computedScheduleRepository.save(buildScheduleEntity(event, computed));

      // Publish within the same Kafka transaction.
      producer.publish(computed, correlationId);

      ack.acknowledge();

      log.info("Processed TimetableChangedEvent [eventId={}] [timetableId={}] [changeType={}]",
          eventId, event.getTimetableId(), event.getChangeType());

    } catch (DataIntegrityViolationException e) {
      // Unique constraint on event_id fired under a race — safe to ack, idempotency maintained.
      log.warn("Race condition on event_id insert — treating as duplicate [timetableId={}]",
          event.getTimetableId());
      ack.acknowledge();
    } finally {
      CorrelationIdHolder.clear();
    }
  }

  private ComputedScheduleEntity buildScheduleEntity(
      TimetableChangedEvent event, ScheduleComputedEvent computed) {

    var entity = new ComputedScheduleEntity();
    entity.setId(UUID.randomUUID());
    entity.setTimetableId(event.getTimetableId().toString());
    entity.setLineId(event.getLineId().toString());
    // Avro logical type 'date' → java.time.LocalDate; no parsing needed.
    entity.setEffectiveDate(event.getEffectiveDate());
    entity.setExpiryDate(event.getExpiryDate());
    entity.setTriggeringEventId(event.getMetadata().getEventId().toString());
    entity.setSchedulePayload(computed.toString());
    entity.setComputedAt(Instant.now());
    return entity;
  }
}
