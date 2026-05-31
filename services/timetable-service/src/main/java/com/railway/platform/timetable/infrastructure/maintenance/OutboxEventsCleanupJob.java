package com.railway.platform.timetable.infrastructure.maintenance;

import com.railway.platform.timetable.infrastructure.persistence.repository.OutboxEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled job that prunes old outbox_events rows.
 *
 * <p>Outbox rows are never deleted by Debezium or the fallback relay. Without cleanup,
 * the table grows unboundedly. This job deletes rows older than {@code outbox.retention-days}
 * (default: 7 days) that have been relay-published (meaning either Debezium or the relay
 * successfully forwarded them).
 *
 * <p>Rows NOT yet relay-published are retained regardless of age — the relay will pick them
 * up on the next run.
 */
@Component
public class OutboxEventsCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventsCleanupJob.class);

    @Value("${outbox.retention-days:7}")
    private int retentionDays;

    private final OutboxEventJpaRepository outboxRepository;

    public OutboxEventsCleanupJob(OutboxEventJpaRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(cron = "${maintenance.outbox.cleanup-cron:0 30 2 * * *}")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = outboxRepository.deleteByRelayPublishedTrueAndCreatedAtBefore(cutoff);
        log.info("OutboxEventsCleanup: deleted {} relay-published outbox rows older than {} days",
                deleted, retentionDays);
    }
}
