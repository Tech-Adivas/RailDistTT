package com.railway.platform.distribution.producer;

import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.NotificationChannel;
import com.railway.platform.events.NotificationRequestEvent;
import com.railway.platform.events.NotificationType;
import com.railway.platform.events.RecipientType;
import com.railway.platform.events.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes NotificationRequestEvents to {@code railway.notification.requests}.
 * Must be called inside a {@code @Transactional("kafkaTransactionManager")} context.
 */
@Component
public class NotificationRequestProducer {

  private static final Logger log = LoggerFactory.getLogger(NotificationRequestProducer.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public NotificationRequestProducer(KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publish(
      String timetableId,
      String lineId,
      String effectiveDate,
      boolean isEmergency,
      String correlationId) {

    var notifType = isEmergency ? NotificationType.EMERGENCY_UPDATE : NotificationType.SCHEDULE_CHANGE;
    var channels = isEmergency
        ? List.of(NotificationChannel.PUSH, NotificationChannel.SMS)
        : List.of(NotificationChannel.PUSH);

    var metadata = EventMetadata.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setEventType(NotificationRequestEvent.class.getName())
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId(correlationId)
        .setActor("system:distribution-service")
        .setSchemaVersion(1)
        .build();

    var event = NotificationRequestEvent.newBuilder()
        .setMetadata(metadata)
        .setNotificationType(notifType)
        .setChannels(channels)
        .setRecipientType(RecipientType.LINE_SUBSCRIBERS)
        .setRecipientIds(List.of())
        .setTitleTemplate(isEmergency
            ? "Emergency schedule update — line {{lineId}}"
            : "Schedule update — line {{lineId}}")
        .setBodyTemplate("The schedule effective {{effectiveDate}} has been updated.")
        .setTemplateVariables(Map.of("lineId", lineId, "effectiveDate", effectiveDate))
        .setIsEmergency(isEmergency)
        .setScheduleAfter(null)
        .build();

    var record = new ProducerRecord<String, Object>(
        Topics.NOTIFICATION_REQUESTS, null, timetableId, event);

    KafkaCorrelationIdPropagator.injectIntoHeaders(record.headers());
    kafkaTemplate.send(record);

    log.info("Published NotificationRequestEvent [timetableId={}] [type={}] [correlationId={}]",
        timetableId, notifType, correlationId);
  }
}
