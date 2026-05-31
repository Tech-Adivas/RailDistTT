package com.railway.platform.query.projector;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.TimetableChangedEvent;
import com.railway.platform.events.Topics;
import com.railway.platform.query.infrastructure.cache.TimetableCacheService;
import com.railway.platform.query.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.query.infrastructure.persistence.entity.TimetableReadModelEntity;
import com.railway.platform.query.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.query.infrastructure.persistence.repository.TimetableReadModelRepository;
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
 * Projects TimetableChangedEvents into the timetable read model.
 *
 * <p>For each event:
 * <ol>
 *   <li>Idempotency check — skip if event_id already processed.</li>
 *   <li>UPSERT the TimetableReadModelEntity (insert or overwrite full state).</li>
 *   <li>Record event_id in processed_events.</li>
 *   <li>Evict the Redis cache entry so the next read fetches fresh data.</li>
 * </ol>
 *
 * <p>The DB write and cache eviction are not atomic — the cache may briefly return stale
 * data after the DB write but before the eviction completes. This is acceptable because:
 * the TTL bounds maximum staleness, and read-after-write within the same request chain
 * routes through the DB anyway.
 */
@Component
public class TimetableProjector {

  private static final Logger log = LoggerFactory.getLogger(TimetableProjector.class);

  private final TimetableReadModelRepository readModelRepository;
  private final ProcessedEventRepository processedEventRepository;
  private final TimetableCacheService cacheService;

  public TimetableProjector(
      TimetableReadModelRepository readModelRepository,
      ProcessedEventRepository processedEventRepository,
      TimetableCacheService cacheService) {
    this.readModelRepository = readModelRepository;
    this.processedEventRepository = processedEventRepository;
    this.cacheService = cacheService;
  }

  @KafkaListener(
      topics = Topics.TIMETABLE_CHANGED,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "timetableChangedListenerContainerFactory")
  @Transactional
  public void project(ConsumerRecord<String, TimetableChangedEvent> record, Acknowledgment ack) {
    TimetableChangedEvent event = record.value();
    String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());
    CorrelationIdHolder.set(correlationId);

    try {
      String eventId = event.getMetadata().getEventId().toString();

      if (processedEventRepository.existsByEventId(eventId)) {
        log.info("Skipping duplicate TimetableChangedEvent [eventId={}]", eventId);
        ack.acknowledge();
        return;
      }

      upsertReadModel(event);
      processedEventRepository.save(
          new ProcessedEventEntity(eventId, TimetableChangedEvent.class.getName()));

      ack.acknowledge();

      // Evict after ack to avoid stale cache if the transaction later rolls back.
      cacheService.evict(event.getTimetableId().toString());

      log.info("Projected TimetableChangedEvent [eventId={}] [timetableId={}] [status={}]",
          eventId, event.getTimetableId(), event.getNewStatus());

    } catch (DataIntegrityViolationException e) {
      log.warn("Race on event_id insert — treating as duplicate [timetableId={}]",
          event.getTimetableId());
      ack.acknowledge();
    } finally {
      CorrelationIdHolder.clear();
    }
  }

  private void upsertReadModel(TimetableChangedEvent event) {
    String timetableId = event.getTimetableId().toString();

    var entity = readModelRepository.findById(UUID.fromString(timetableId))
        .orElseGet(TimetableReadModelEntity::new);

    entity.setId(UUID.fromString(timetableId));
    entity.setLineId(event.getLineId().toString());
    // Name is not in TimetableChangedEvent — query the write-side API or carry it in the event.
    // For now we preserve existing name (empty string for new records as placeholder).
    // TODO: Phase 4 — enrich event with name/description or fetch from timetable-service.
    if (entity.getName() == null) {
      entity.setName(timetableId); // fallback placeholder
    }
    entity.setStatus(event.getNewStatus().toString());
    entity.setEffectiveDate(event.getEffectiveDate());
    entity.setExpiryDate(event.getExpiryDate());
    entity.setVersion(event.getVersion());
    entity.setLastEventId(event.getMetadata().getEventId().toString());
    entity.setLastUpdatedAt(Instant.ofEpochMilli(event.getMetadata().getOccurredAt()));
    // authorId: not in current event schema — preserve existing or set from actor
    if (entity.getAuthorId() == null) {
      entity.setAuthorId(event.getMetadata().getActor().toString());
    }

    readModelRepository.save(entity);
  }
}
