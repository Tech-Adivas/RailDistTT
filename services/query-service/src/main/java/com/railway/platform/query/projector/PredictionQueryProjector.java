package com.railway.platform.query.projector;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.correlation.KafkaCorrelationIdPropagator;
import com.railway.platform.events.DelayPredictionEvent;
import com.railway.platform.events.Topics;
import com.railway.platform.query.application.PredictionQueryService;
import com.railway.platform.query.infrastructure.persistence.entity.DelayPredictionEntity;
import com.railway.platform.query.infrastructure.persistence.entity.ProcessedEventEntity;
import com.railway.platform.query.infrastructure.persistence.repository.DelayPredictionRepository;
import com.railway.platform.query.infrastructure.persistence.repository.ProcessedEventRepository;
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
 * Projects {@link DelayPredictionEvent}s into the {@code delay_predictions} read model table.
 *
 * <p>For each event:
 * <ol>
 *   <li>Set correlation ID from Kafka headers.</li>
 *   <li>Idempotency check — skip if {@code event_id} already in {@code processed_events}.</li>
 *   <li>Insert a new {@link DelayPredictionEntity} row.</li>
 *   <li>Record event_id in {@code processed_events}.</li>
 *   <li>Invalidate the Redis cache for this route.</li>
 *   <li>Acknowledge the Kafka offset.</li>
 * </ol>
 *
 * <p>Race condition duplicate inserts (concurrent consumers / rebalance) are caught via
 * {@link DataIntegrityViolationException} from the UNIQUE constraint on {@code event_id},
 * and are silently acknowledged.
 */
@Component
public class PredictionQueryProjector {

  private static final Logger log = LoggerFactory.getLogger(PredictionQueryProjector.class);

  private final DelayPredictionRepository delayPredictionRepository;
  private final ProcessedEventRepository processedEventRepository;
  private final PredictionQueryService predictionQueryService;

  public PredictionQueryProjector(
      DelayPredictionRepository delayPredictionRepository,
      ProcessedEventRepository processedEventRepository,
      PredictionQueryService predictionQueryService) {
    this.delayPredictionRepository = delayPredictionRepository;
    this.processedEventRepository = processedEventRepository;
    this.predictionQueryService = predictionQueryService;
  }

  @KafkaListener(
      topics = Topics.DELAY_PREDICTION_COMPUTED,
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "delayPredictionListenerContainerFactory")
  @Transactional
  public void project(ConsumerRecord<String, DelayPredictionEvent> record, Acknowledgment ack) {
    DelayPredictionEvent event = record.value();
    String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());
    CorrelationIdHolder.set(correlationId);

    try {
      String eventId = event.getMetadata().getEventId().toString();

      if (processedEventRepository.existsByEventId(eventId)) {
        log.info("Skipping duplicate DelayPredictionEvent [eventId={}]", eventId);
        ack.acknowledge();
        return;
      }

      var entity = new DelayPredictionEntity(
          event.getRouteId(),
          event.getTrainId(),
          event.getPredictedDelayMinutes(),
          event.getConfidenceScore(),
          event.getModelVersion(),
          Instant.now(),
          eventId);

      delayPredictionRepository.save(entity);
      processedEventRepository.save(
          new ProcessedEventEntity(eventId, DelayPredictionEvent.class.getName()));

      predictionQueryService.invalidateCache(event.getRouteId());

      ack.acknowledge();

      log.info(
          "Projected DelayPredictionEvent [eventId={}] [routeId={}] [delayMin={}] [confidence={}]",
          eventId,
          event.getRouteId(),
          event.getPredictedDelayMinutes(),
          event.getConfidenceScore());

    } catch (DataIntegrityViolationException e) {
      log.warn(
          "Race condition on event_id insert — treating as duplicate [routeId={}]",
          event.getRouteId());
      ack.acknowledge();
    } finally {
      CorrelationIdHolder.clear();
    }
  }
}
