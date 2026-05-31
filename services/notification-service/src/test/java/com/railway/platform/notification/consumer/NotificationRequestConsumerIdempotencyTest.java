package com.railway.platform.notification.consumer;

import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.NotificationChannel;
import com.railway.platform.events.NotificationRequestEvent;
import com.railway.platform.events.NotificationType;
import com.railway.platform.events.RecipientType;
import com.railway.platform.notification.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.notification.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.notification.service.NotificationDeliveryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationRequestConsumerIdempotencyTest {

  @Mock private NotificationDeliveryService deliveryService;
  @Mock private ProcessedEventRepository processedEventRepository;
  @Mock private Acknowledgment ack;

  @InjectMocks
  private NotificationRequestConsumer consumer;

  @Test
  void whenEventAlreadyProcessed_thenDeliverySkipped() {
    var eventId = UUID.randomUUID().toString();
    var event = buildEvent(eventId);
    var record = new ConsumerRecord<>("railway.notification.requests", 0, 0L, "key", event);

    when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

    consumer.consume(record, ack);

    verifyNoInteractions(deliveryService);
    verify(ack).acknowledge();
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  void whenEventIsNew_thenDeliveryInvokedAndEventRecorded() {
    var eventId = UUID.randomUUID().toString();
    var event = buildEvent(eventId);
    var record = new ConsumerRecord<>("railway.notification.requests", 0, 0L, "key", event);

    when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);

    consumer.consume(record, ack);

    verify(deliveryService).deliver(eq(event), any());
    verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    verify(ack).acknowledge();
  }

  private NotificationRequestEvent buildEvent(String eventId) {
    var metadata = EventMetadata.newBuilder()
        .setEventId(eventId)
        .setEventType("NotificationRequestEvent")
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId("test-correlation")
        .setActor("system:distribution-service")
        .setSchemaVersion(1)
        .build();

    return NotificationRequestEvent.newBuilder()
        .setMetadata(metadata)
        .setNotificationType(NotificationType.SCHEDULE_CHANGE)
        .setChannels(List.of(NotificationChannel.PUSH))
        .setRecipientType(RecipientType.LINE_SUBSCRIBERS)
        .setRecipientIds(List.of())
        .setTitleTemplate("Schedule update for line {{lineId}}")
        .setBodyTemplate("The schedule has been updated.")
        .setTemplateVariables(Map.of("lineId", "GWR-PAD-BRI"))
        .setIsEmergency(false)
        .setScheduleAfter(null)
        .build();
  }
}
