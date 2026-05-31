package com.railway.platform.distribution.producer;

import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.DistributionChannel;
import com.railway.platform.events.DistributionEvent;
import com.railway.platform.events.DistributionStatus;
import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes DistributionEvent records to {@code railway.distribution.events} within the
 * caller's Kafka transaction. Must only be called from a method annotated with
 * {@code @Transactional("kafkaTransactionManager")}.
 *
 * <p>timetableId is used as the message key so all tracking events for a given timetable
 * land on the same partition, preserving ordering for the saga state machine.
 */
@Component
public class DistributionEventProducer {

  private static final Logger log = LoggerFactory.getLogger(DistributionEventProducer.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public DistributionEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publish(
      String scheduleComputedEventId,
      String timetableId,
      DistributionChannel channel,
      DistributionStatus status,
      String failureReason,
      boolean isEmergency,
      String correlationId) {

    var metadata = EventMetadata.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setEventType(DistributionEvent.class.getName())
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId(correlationId)
        .setActor("system:distribution-service")
        .setSchemaVersion(1)
        .build();

    var event = DistributionEvent.newBuilder()
        .setMetadata(metadata)
        .setScheduleComputedEventId(scheduleComputedEventId)
        .setTimetableId(timetableId)
        .setChannel(channel)
        .setStatus(status)
        .setFailureReason(failureReason)
        .setIsEmergency(isEmergency)
        .build();

    var record = new ProducerRecord<String, Object>(
        Topics.DISTRIBUTION_EVENTS,
        null,
        timetableId,
        event);

    KafkaCorrelationIdPropagator.injectIntoHeaders(record.headers());
    kafkaTemplate.send(record);

    log.info("Published DistributionEvent [timetableId={}] [channel={}] [status={}] [correlationId={}]",
        timetableId, channel, status, correlationId);
  }
}
