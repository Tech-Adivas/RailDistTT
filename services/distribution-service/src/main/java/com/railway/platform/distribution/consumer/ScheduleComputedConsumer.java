package com.railway.platform.distribution.consumer;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.distribution.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.distribution.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.distribution.orchestrator.DistributionOrchestrator;
import com.railway.platform.events.ScheduleComputedEvent;
import com.railway.platform.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer for ScheduleComputedEvent.
 *
 * <p>Exactly-once semantics: the idempotency row insertion, distribution tracking writes,
 * and all DistributionEvent/NotificationRequestEvent Kafka publishes are wrapped in a single
 * {@code KafkaTransactionManager} transaction. An aborted Kafka transaction rolls back the
 * DB writes too; a DB failure aborts the Kafka transaction.
 */
@Component
public class ScheduleComputedConsumer {

  private static final Logger log = LoggerFactory.getLogger(ScheduleComputedConsumer.class);

  private final DistributionOrchestrator orchestrator;
  private final ProcessedEventRepository processedEventRepository;

  public ScheduleComputedConsumer(
      DistributionOrchestrator orchestrator,
      ProcessedEventRepository processedEventRepository) {
    this.orchestrator = orchestrator;
    this.processedEventRepository = processedEventRepository;
  }

  @KafkaListener(
      topics = Topics.SCHEDULE_COMPUTED,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "scheduleComputedListenerContainerFactory")
  @Transactional("kafkaTransactionManager")
  public void consume(ConsumerRecord<String, ScheduleComputedEvent> record, Acknowledgment ack) {
    ScheduleComputedEvent event = record.value();
    String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());
    CorrelationIdHolder.set(correlationId);

    try {
      String eventId = event.getMetadata().getEventId().toString();

      if (processedEventRepository.existsByEventId(eventId)) {
        log.info("Skipping duplicate ScheduleComputedEvent [eventId={}] [timetableId={}]",
            eventId, event.getTimetableId());
        ack.acknowledge();
        return;
      }

      orchestrator.distribute(event, correlationId);

      processedEventRepository.save(
          new ProcessedEventEntity(eventId, ScheduleComputedEvent.class.getName()));

      ack.acknowledge();

      log.info("Distributed ScheduleComputedEvent [eventId={}] [timetableId={}] [lineId={}]",
          eventId, event.getTimetableId(), event.getLineId());

    } catch (DataIntegrityViolationException e) {
      log.warn("Race condition on event_id insert — treating as duplicate [timetableId={}]",
          event.getTimetableId());
      ack.acknowledge();
    } finally {
      CorrelationIdHolder.clear();
    }
  }
}
