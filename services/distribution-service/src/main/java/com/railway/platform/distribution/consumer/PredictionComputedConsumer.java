package com.railway.platform.distribution.consumer;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.distribution.channel.PredictionWebSocketDistributor;
import com.railway.platform.distribution.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.distribution.infrastructure.persistence.repository.ProcessedEventRepository;
import com.railway.platform.events.DelayPredictionEvent;
import com.railway.platform.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer for {@link DelayPredictionEvent}.
 *
 * <p>On each event:
 * <ol>
 *   <li>Set correlation ID from Kafka headers.</li>
 *   <li>Check idempotency via {@code ProcessedEventRepository} — skip if duplicate.</li>
 *   <li>Push the prediction to WebSocket subscribers via {@link PredictionWebSocketDistributor}.</li>
 *   <li>Record the event ID in {@code processed_events}.</li>
 *   <li>Acknowledge the Kafka offset.</li>
 * </ol>
 *
 * <p>Unlike {@code ScheduleComputedConsumer}, this consumer does NOT participate in a Kafka
 * transaction because WebSocket delivery is fire-and-forget and does not produce downstream
 * Kafka messages. A regular {@code @Transactional} is used for the DB idempotency write only.
 */
@Component
public class PredictionComputedConsumer {

  private static final Logger log = LoggerFactory.getLogger(PredictionComputedConsumer.class);

  private final PredictionWebSocketDistributor predictionWebSocketDistributor;
  private final ProcessedEventRepository processedEventRepository;

  public PredictionComputedConsumer(
      PredictionWebSocketDistributor predictionWebSocketDistributor,
      ProcessedEventRepository processedEventRepository) {
    this.predictionWebSocketDistributor = predictionWebSocketDistributor;
    this.processedEventRepository = processedEventRepository;
  }

  @KafkaListener(
      topics = Topics.DELAY_PREDICTION_COMPUTED,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "delayPredictionListenerContainerFactory")
  @Transactional
  public void consume(ConsumerRecord<String, DelayPredictionEvent> record, Acknowledgment ack) {
    DelayPredictionEvent event = record.value();
    String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());
    CorrelationIdHolder.set(correlationId);

    try {
      String eventId = event.getMetadata().getEventId().toString();

      if (processedEventRepository.existsByEventId(eventId)) {
        log.info("Skipping duplicate DelayPredictionEvent [eventId={}] [routeId={}]",
            eventId, event.getRouteId());
        ack.acknowledge();
        return;
      }

      predictionWebSocketDistributor.distribute(event, correlationId);

      processedEventRepository.save(
          new ProcessedEventEntity(eventId, DelayPredictionEvent.class.getName()));

      ack.acknowledge();

      log.info("Distributed DelayPredictionEvent [eventId={}] [routeId={}] [delayMin={}]",
          eventId, event.getRouteId(), event.getPredictedDelayMinutes());

    } catch (DataIntegrityViolationException e) {
      log.warn("Race condition on event_id insert — treating as duplicate [routeId={}]",
          event.getRouteId());
      ack.acknowledge();
    } finally {
      CorrelationIdHolder.clear();
    }
  }
}
