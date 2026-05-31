package com.railway.platform.timetable.infrastructure.relay;

import com.railway.platform.timetable.infrastructure.persistence.entity.OutboxEventJpaEntity;
import com.railway.platform.timetable.infrastructure.persistence.repository.OutboxEventJpaRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Fallback relay for the Transactional Outbox.
 *
 * <p>Debezium CDC is the primary outbox-to-Kafka relay. This component is the safety net:
 * it runs every {@code outbox.relay.delay-ms} (default: 5 minutes) and publishes any
 * outbox rows that are older than the lag threshold and have not been marked as relay-published.
 *
 * <p>Because downstream consumers are idempotent (processed_events.event_id UNIQUE),
 * duplicate publishes from both Debezium and this relay are safe — the consumer silently
 * acknowledges the duplicate without re-processing.
 *
 * <p>The relay marks each row {@code relay_published=true} after a successful send so it is
 * not published again on the next scheduled run. Rows are never deleted by either the relay
 * or Debezium; a separate {@link com.railway.platform.timetable.infrastructure.maintenance.OutboxEventsCleanupJob} handles retention.
 */
@Component
public class OutboxFallbackRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxFallbackRelay.class);

    // Topic for timetable changed events — must match Debezium outbox event router config.
    private static final String TIMETABLE_CHANGED_TOPIC = "railway.timetable.changed";

    @Value("${outbox.relay.lag-threshold-minutes:5}")
    private int lagThresholdMinutes;

    private final OutboxEventJpaRepository outboxRepository;
    private final KafkaTemplate<String, Object> relayKafkaTemplate;

    public OutboxFallbackRelay(
            OutboxEventJpaRepository outboxRepository,
            @Qualifier("relayKafkaTemplate") KafkaTemplate<String, Object> relayKafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.relayKafkaTemplate = relayKafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.delay-ms:300000}")
    @Transactional
    public void relayUnpublishedEvents() {
        Instant threshold = Instant.now().minus(lagThresholdMinutes, ChronoUnit.MINUTES);
        List<OutboxEventJpaEntity> stale =
                outboxRepository.findByRelayPublishedFalseAndCreatedAtBefore(threshold);

        if (stale.isEmpty()) {
            return;
        }

        log.warn("OutboxFallbackRelay: {} stale outbox rows found (Debezium may be lagging)",
                stale.size());

        int published = 0;
        for (OutboxEventJpaEntity row : stale) {
            try {
                // Forward the raw JSON payload. Downstream consumers parse via Debezium's
                // outbox event router convention. Key = aggregateId for partition ordering.
                var record = new ProducerRecord<String, Object>(
                        TIMETABLE_CHANGED_TOPIC,
                        row.getAggregateId(),
                        row.getPayload());
                record.headers().add("eventType", row.getEventType().getBytes());
                record.headers().add("correlationId", row.getCorrelationId().getBytes());

                relayKafkaTemplate.send(record).get(); // wait for ack
                row.setRelayPublished(true);
                outboxRepository.save(row);
                published++;
            } catch (Exception ex) {
                log.error("OutboxFallbackRelay: failed to relay outbox row [id={}] [error={}]",
                        row.getId(), ex.getMessage());
            }
        }

        log.info("OutboxFallbackRelay: relayed {}/{} stale outbox rows", published, stale.size());
    }
}
