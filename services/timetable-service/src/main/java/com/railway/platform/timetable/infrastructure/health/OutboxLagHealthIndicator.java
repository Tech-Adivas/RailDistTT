package com.railway.platform.timetable.infrastructure.health;

import com.railway.platform.timetable.infrastructure.persistence.repository.OutboxEventJpaRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Health indicator that warns if the outbox table has rows older than the Debezium
 * CDC lag threshold (5 minutes). A stale outbox indicates Debezium may be down or falling
 * behind, which means domain events are not being published to Kafka.
 */
@Component("outboxLag")
public class OutboxLagHealthIndicator implements HealthIndicator {

    private static final int LAG_THRESHOLD_MINUTES = 5;

    private final OutboxEventJpaRepository outboxRepository;

    public OutboxLagHealthIndicator(OutboxEventJpaRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    public Health health() {
        Instant threshold = Instant.now().minus(LAG_THRESHOLD_MINUTES, ChronoUnit.MINUTES);
        long staleCount = outboxRepository.countByCreatedAtBefore(threshold);

        if (staleCount > 0) {
            return Health.down()
                    .withDetail("staleOutboxRows", staleCount)
                    .withDetail("lagThresholdMinutes", LAG_THRESHOLD_MINUTES)
                    .withDetail("message", "Debezium may be lagging or down — " + staleCount + " outbox rows not yet relayed")
                    .build();
        }
        return Health.up()
                .withDetail("staleOutboxRows", 0)
                .withDetail("lagThresholdMinutes", LAG_THRESHOLD_MINUTES)
                .build();
    }
}
