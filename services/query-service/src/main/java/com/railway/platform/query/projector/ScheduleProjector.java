package com.railway.platform.query.projector;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.ScheduleComputedEvent;
import com.railway.platform.events.Topics;
import com.railway.platform.query.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.query.infrastructure.persistence.entity.ScheduleReadModelEntity;
import com.railway.platform.query.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.query.infrastructure.persistence.repository.ScheduleReadModelRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Projects ScheduleComputedEvents into the schedule read model.
 *
 * <p>Each event represents the latest computation for a timetable, so the projector
 * inserts a new row (rather than upserting by timetableId) to preserve the history of
 * recomputations. The {@code findTopByTimetableIdOrderByComputedAtDesc} query in the
 * read service returns only the most recent row per timetable.
 */
@Component
public class ScheduleProjector {

  private static final Logger log = LoggerFactory.getLogger(ScheduleProjector.class);

  private final ScheduleReadModelRepository scheduleRepository;
  private final ProcessedEventRepository processedEventRepository;

  public ScheduleProjector(
      ScheduleReadModelRepository scheduleRepository,
      ProcessedEventRepository processedEventRepository) {
    this.scheduleRepository = scheduleRepository;
    this.processedEventRepository = processedEventRepository;
  }

  @KafkaListener(
      topics = Topics.SCHEDULE_COMPUTED,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "scheduleComputedListenerContainerFactory")
  @Transactional
  public void project(ConsumerRecord<String, ScheduleComputedEvent> record, Acknowledgment ack) {
    ScheduleComputedEvent event = record.value();
    String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());
    CorrelationIdHolder.set(correlationId);

    try {
      String eventId = event.getMetadata().getEventId().toString();

      if (processedEventRepository.existsByEventId(eventId)) {
        log.info("Skipping duplicate ScheduleComputedEvent [eventId={}]", eventId);
        ack.acknowledge();
        return;
      }

      var entity = new ScheduleReadModelEntity();
      entity.setId(UUID.randomUUID());
      entity.setTimetableId(event.getTimetableId().toString());
      entity.setLineId(event.getLineId().toString());
      entity.setEffectiveDate(event.getEffectiveDate());
      entity.setExpiryDate(event.getExpiryDate());
      entity.setScheduleData(event.toString());
      entity.setTriggeringEventId(event.getTriggeringEventId().toString());
      entity.setComputedAt(Instant.ofEpochMilli(event.getMetadata().getOccurredAt()));

      scheduleRepository.save(entity);
      processedEventRepository.save(
          new ProcessedEventEntity(eventId, ScheduleComputedEvent.class.getName()));

      ack.acknowledge();

      log.info("Projected ScheduleComputedEvent [eventId={}] [timetableId={}]",
          eventId, event.getTimetableId());

    } catch (DataIntegrityViolationException e) {
      log.warn("Race on event_id insert — treating as duplicate [timetableId={}]",
          event.getTimetableId());
      ack.acknowledge();
    } finally {
      CorrelationIdHolder.clear();
    }
  }
}
