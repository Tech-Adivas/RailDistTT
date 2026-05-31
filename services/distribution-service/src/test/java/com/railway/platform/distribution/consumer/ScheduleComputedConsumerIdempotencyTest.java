package com.railway.platform.distribution.consumer;

import com.railway.platform.distribution.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.distribution.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.distribution.orchestrator.DistributionOrchestrator;
import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.ScheduleComputedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleComputedConsumerIdempotencyTest {

  @Mock private DistributionOrchestrator orchestrator;
  @Mock private ProcessedEventRepository processedEventRepository;
  @Mock private Acknowledgment ack;

  @InjectMocks
  private ScheduleComputedConsumer consumer;

  @Test
  void whenEventAlreadyProcessed_thenOrchestratorSkippedAndAcknowledges() {
    var eventId = UUID.randomUUID().toString();
    var event = buildEvent(eventId);
    var record = new ConsumerRecord<>("railway.schedule.computed", 0, 0L,
        event.getTimetableId().toString(), event);

    when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

    consumer.consume(record, ack);

    verifyNoInteractions(orchestrator);
    verify(ack).acknowledge();
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  void whenEventIsNew_thenOrchestratorCalledAndEventRecorded() {
    var eventId = UUID.randomUUID().toString();
    var event = buildEvent(eventId);
    var record = new ConsumerRecord<>("railway.schedule.computed", 0, 0L,
        event.getTimetableId().toString(), event);

    when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);

    consumer.consume(record, ack);

    verify(orchestrator).distribute(eq(event), any());
    verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    verify(ack).acknowledge();
  }

  private ScheduleComputedEvent buildEvent(String eventId) {
    var metadata = EventMetadata.newBuilder()
        .setEventId(eventId)
        .setEventType("ScheduleComputedEvent")
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId("test-correlation")
        .setActor("system:schedule-service")
        .setSchemaVersion(1)
        .build();

    return ScheduleComputedEvent.newBuilder()
        .setMetadata(metadata)
        .setTimetableId(UUID.randomUUID().toString())
        .setLineId("GWR-PAD-BRI")
        .setEffectiveDate(LocalDate.of(2026, 6, 1))
        .setExpiryDate(LocalDate.of(2026, 12, 31))
        .setServices(List.of())
        .setTriggeringEventId(UUID.randomUUID().toString())
        .build();
  }
}
