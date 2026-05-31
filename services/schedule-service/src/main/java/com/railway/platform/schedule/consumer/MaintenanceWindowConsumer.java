package com.railway.platform.schedule.consumer;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.MaintenanceWindowEvent;
import com.railway.platform.events.Topics;
import com.railway.platform.schedule.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.schedule.infrastructure.persistence.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer for MaintenanceWindowEvent.
 *
 * <p>Records the maintenance window so that the next schedule computation pass can apply the
 * window constraints to affected track segments. The actual application of maintenance windows
 * to computed schedules is handled by {@link com.railway.platform.schedule.service.ScheduleComputationService}.
 *
 * <p>Same idempotency pattern as {@link TimetableChangedConsumer}: event_id unique constraint
 * guards against duplicate processing under at-least-once delivery.
 */
@Component
public class MaintenanceWindowConsumer {

  private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowConsumer.class);

  private final ProcessedEventRepository processedEventRepository;

  public MaintenanceWindowConsumer(ProcessedEventRepository processedEventRepository) {
    this.processedEventRepository = processedEventRepository;
  }

  @KafkaListener(
      topics = Topics.MAINTENANCE_WINDOWS,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "maintenanceWindowListenerContainerFactory")
  @Transactional("kafkaTransactionManager")
  public void consume(ConsumerRecord<String, MaintenanceWindowEvent> record, Acknowledgment ack) {
    MaintenanceWindowEvent event = record.value();
    String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());
    CorrelationIdHolder.set(correlationId);

    try {
      String eventId = event.getMetadata().getEventId().toString();

      if (processedEventRepository.existsByEventId(eventId)) {
        log.info("Skipping duplicate MaintenanceWindowEvent [eventId={}]", eventId);
        ack.acknowledge();
        return;
      }

      // Record the maintenance window in processed_events for idempotency.
      // TODO: Persist the maintenance window details to a dedicated table so
      //       ScheduleComputationService can apply them when computing schedules.
      processedEventRepository.save(
          new ProcessedEventEntity(eventId, MaintenanceWindowEvent.class.getName()));

      ack.acknowledge();

      log.info("Recorded MaintenanceWindowEvent [eventId={}] [windowId={}] [trackSegmentId={}]",
          eventId, event.getWindowId(), event.getTrackSegmentId());

    } catch (DataIntegrityViolationException e) {
      log.warn("Race condition on event_id insert — treating as duplicate");
      ack.acknowledge();
    } finally {
      CorrelationIdHolder.clear();
    }
  }
}
