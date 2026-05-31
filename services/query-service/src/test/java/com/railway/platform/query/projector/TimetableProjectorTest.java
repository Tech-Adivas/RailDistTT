package com.railway.platform.query.projector;

import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.TimetableChangeType;
import com.railway.platform.events.TimetableChangedEvent;
import com.railway.platform.query.infrastructure.cache.TimetableCacheService;
import com.railway.platform.query.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.query.infrastructure.persistence.entity.TimetableReadModelEntity;
import com.railway.platform.query.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.query.infrastructure.persistence.repository.TimetableReadModelRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimetableProjectorTest {

  @Mock private TimetableReadModelRepository readModelRepository;
  @Mock private ProcessedEventRepository processedEventRepository;
  @Mock private TimetableCacheService cacheService;
  @Mock private Acknowledgment ack;

  @InjectMocks
  private TimetableProjector projector;

  @Test
  void whenEventIsNew_thenReadModelIsUpsertedAndCacheEvicted() {
    var eventId = UUID.randomUUID().toString();
    var timetableId = UUID.randomUUID().toString();
    var event = buildEvent(eventId, timetableId, "APPROVED");
    var record = new ConsumerRecord<>("railway.timetable.changed", 0, 0L, timetableId, event);

    when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);
    when(readModelRepository.findById(any())).thenReturn(Optional.empty());
    when(readModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    projector.project(record, ack);

    var entityCaptor = ArgumentCaptor.forClass(TimetableReadModelEntity.class);
    verify(readModelRepository).save(entityCaptor.capture());
    assertThat(entityCaptor.getValue().getStatus()).isEqualTo("APPROVED");

    verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    verify(ack).acknowledge();
    verify(cacheService).evict(timetableId);
  }

  @Test
  void whenEventIsDuplicate_thenSkipsProjectionAndAcknowledges() {
    var eventId = UUID.randomUUID().toString();
    var timetableId = UUID.randomUUID().toString();
    var event = buildEvent(eventId, timetableId, "APPROVED");
    var record = new ConsumerRecord<>("railway.timetable.changed", 0, 0L, timetableId, event);

    when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

    projector.project(record, ack);

    verify(readModelRepository, never()).save(any());
    verify(cacheService, never()).evict(any());
    verify(ack).acknowledge();
  }

  @Test
  void whenExistingEntityFound_thenStatusIsUpdated() {
    var eventId = UUID.randomUUID().toString();
    var timetableId = UUID.randomUUID().toString();
    var event = buildEvent(eventId, timetableId, "ACTIVE");
    var record = new ConsumerRecord<>("railway.timetable.changed", 0, 0L, timetableId, event);

    var existing = new TimetableReadModelEntity();
    existing.setId(UUID.fromString(timetableId));
    existing.setName("My Timetable");
    existing.setAuthorId("user-123");
    existing.setStatus("APPROVED");

    when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);
    when(readModelRepository.findById(UUID.fromString(timetableId))).thenReturn(Optional.of(existing));
    when(readModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    projector.project(record, ack);

    var entityCaptor = ArgumentCaptor.forClass(TimetableReadModelEntity.class);
    verify(readModelRepository).save(entityCaptor.capture());

    var saved = entityCaptor.getValue();
    assertThat(saved.getStatus()).isEqualTo("ACTIVE");
    assertThat(saved.getName()).isEqualTo("My Timetable"); // preserved
    assertThat(saved.getAuthorId()).isEqualTo("user-123"); // preserved
  }

  private TimetableChangedEvent buildEvent(String eventId, String timetableId, String newStatus) {
    var metadata = EventMetadata.newBuilder()
        .setEventId(eventId)
        .setEventType("TimetableChangedEvent")
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId("test-correlation")
        .setActor("test-actor")
        .setSchemaVersion(1)
        .build();

    return TimetableChangedEvent.newBuilder()
        .setMetadata(metadata)
        .setTimetableId(timetableId)
        .setLineId("GWR-PAD-BRI")
        .setChangeType(TimetableChangeType.APPROVED)
        .setVersion(2L)
        .setPreviousStatus("PENDING_REVIEW")
        .setNewStatus(newStatus)
        .setEffectiveDate(LocalDate.of(2026, 6, 1))
        .setExpiryDate(LocalDate.of(2026, 12, 31))
        .build();
  }
}
