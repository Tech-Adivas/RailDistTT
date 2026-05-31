package com.railway.platform.notification.consumer;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.NotificationRequestEvent;
import com.railway.platform.events.Topics;
import com.railway.platform.notification.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.notification.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.notification.service.NotificationDeliveryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Idempotent consumer for NotificationRequestEvent.
 *
 * <p>Idempotency is enforced via the {@code processed_events} unique constraint on event_id.
 * Duplicate delivery from Kafka at-least-once semantics will be silently skipped.
 *
 * <p>{@code scheduleAfter}: events with a future delivery time are currently processed
 * immediately with a warning. Scheduled delivery requires a separate scheduler component
 * (planned for Phase 5). The idempotency guard ensures re-delivery after the window opens
 * will also be a no-op — so any Phase 5 scheduler must use a separate idempotency key.
 */
@Component
public class NotificationRequestConsumer {

  private static final Logger log = LoggerFactory.getLogger(NotificationRequestConsumer.class);

  private final NotificationDeliveryService deliveryService;
  private final ProcessedEventRepository processedEventRepository;

  public NotificationRequestConsumer(
      NotificationDeliveryService deliveryService,
      ProcessedEventRepository processedEventRepository) {
    this.deliveryService = deliveryService;
    this.processedEventRepository = processedEventRepository;
  }

  @KafkaListener(
      topics = Topics.NOTIFICATION_REQUESTS,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "notificationRequestListenerContainerFactory")
  @Transactional
  public void consume(ConsumerRecord<String, NotificationRequestEvent> record, Acknowledgment ack) {
    NotificationRequestEvent event = record.value();
    String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());
    CorrelationIdHolder.set(correlationId);

    try {
      String eventId = event.getMetadata().getEventId().toString();

      if (processedEventRepository.existsByEventId(eventId)) {
        log.info("Skipping duplicate NotificationRequestEvent [eventId={}]", eventId);
        ack.acknowledge();
        return;
      }

      // Warn if the event has a future scheduleAfter time — process immediately as a fallback.
      Long scheduleAfter = event.getScheduleAfter();
      if (scheduleAfter != null && Instant.now().isBefore(Instant.ofEpochMilli(scheduleAfter))) {
        log.warn("NotificationRequestEvent [eventId={}] has scheduleAfter={} in the future — "
            + "processing immediately (scheduled delivery not yet implemented)",
            eventId, scheduleAfter);
      }

      deliveryService.deliver(event, correlationId);

      processedEventRepository.save(
          new ProcessedEventEntity(eventId, NotificationRequestEvent.class.getName()));

      ack.acknowledge();

      log.info("Processed NotificationRequestEvent [eventId={}] [type={}] [isEmergency={}]",
          eventId, event.getNotificationType(), event.getIsEmergency());

    } catch (DataIntegrityViolationException e) {
      log.warn("Race condition on event_id insert — treating as duplicate");
      ack.acknowledge();
    } finally {
      CorrelationIdHolder.clear();
    }
  }
}
