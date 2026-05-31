package com.railway.platform.schedule.consumer;

import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.TimetableChangeType;
import com.railway.platform.events.TimetableChangedEvent;
import com.railway.platform.schedule.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.schedule.infrastructure.persistence.repository.ComputedScheduleRepository;
import com.railway.platform.schedule.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.schedule.producer.ScheduleComputedProducer;
import com.railway.platform.schedule.service.ScheduleComputationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for idempotency logic in TimetableChangedConsumer.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyTest {

  @Mock private ScheduleComputationService computationService;
  @Mock private ScheduleComputedProducer producer;
  @Mock private ProcessedEventRepository processedEventRepository;
  @Mock private ComputedScheduleRepository computedScheduleRepository;
  @Mock private KafkaTemplate<String, Object> dlqTemplate;
  @Mock private Acknowledgment ack;

  @InjectMocks
  private TimetableChangedConsumer consumer;

  @Test
  void whenEventAlreadyProcessed_thenSkipsComputationAndAcknowledges() {
    var event = buildEvent("duplicate-event-id");
    var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>(
        "railway.timetable.changed", 0, 0L, "key", event);

    when(processedEventRepository.existsByEventId("duplicate-event-id")).thenReturn(true);

    consumer.consume(record, ack);

    verifyNoInteractions(computationService);
    verifyNoInteractions(producer);
    verify(ack).acknowledge();
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  void whenEventIsNew_thenComputesAndPublishes() {
    var eventId = UUID.randomUUID().toString();
    var event = buildEvent(eventId);
    var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>(
        "railway.timetable.changed", 0, 0L, "key", event);
    record.headers().add("correlationId", "test-corr-id".getBytes());

    when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);
    var computed = mock(com.railway.platform.events.ScheduleComputedEvent.class);
    when(computed.toString()).thenReturn("{}");
    when(computed.getTimetableId()).thenReturn("tid");
    when(computed.getLineId()).thenReturn("lid");
    when(computed.getMetadata()).thenReturn(buildMetadata(UUID.randomUUID().toString()));
    when(computationService.compute(any(), any())).thenReturn(computed);

    consumer.consume(record, ack);

    verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    verify(computedScheduleRepository).save(any());
    verify(producer).publish(any(), any());
    verify(ack).acknowledge();
  }

  @Test
  void whenSameEventDeliveredTwice_thenSecondCallIsNoOp() {
    var eventId = UUID.randomUUID().toString();
    var event = buildEvent(eventId);
    var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>(
        "railway.timetable.changed", 0, 0L, "key", event);

    // First call: not yet processed
    when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);
    var computed = mock(com.railway.platform.events.ScheduleComputedEvent.class);
    when(computed.toString()).thenReturn("{}");
    when(computed.getTimetableId()).thenReturn("tid");
    when(computed.getLineId()).thenReturn("lid");
    when(computed.getMetadata()).thenReturn(buildMetadata(UUID.randomUUID().toString()));
    when(computationService.compute(any(), any())).thenReturn(computed);
    consumer.consume(record, ack);

    // Second call: already processed
    when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);
    consumer.consume(record, ack);

    // computationService called only once
    verify(computationService, times(1)).compute(any(), any());
    verify(ack, times(2)).acknowledge();
  }

  private TimetableChangedEvent buildEvent(String eventId) {
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
        .setTimetableId(UUID.randomUUID().toString())
        .setLineId("GWR-PAD-BRI")
        .setChangeType(TimetableChangeType.APPROVED)
        .setVersion(1L)
        .setPreviousStatus("PENDING_REVIEW")
        .setNewStatus("APPROVED")
        .setEffectiveDate(LocalDate.of(2026, 6, 1))
        .setExpiryDate(LocalDate.of(2026, 12, 31))
        .build();
  }

  private EventMetadata buildMetadata(String eventId) {
    return EventMetadata.newBuilder()
        .setEventId(eventId)
        .setEventType("ScheduleComputedEvent")
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId("test-correlation")
        .setActor("system:schedule-service")
        .setSchemaVersion(1)
        .build();
  }
}
