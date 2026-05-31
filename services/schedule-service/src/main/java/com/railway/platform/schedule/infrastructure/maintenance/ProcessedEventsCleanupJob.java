package com.railway.platform.schedule.infrastructure.maintenance;

import com.railway.platform.schedule.infrastructure.persistence.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class ProcessedEventsCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventsCleanupJob.class);
    private static final int RETENTION_DAYS = 30;

    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventsCleanupJob(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Scheduled(cron = "${maintenance.processed-events.cleanup-cron:0 0 2 * * *}")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = processedEventRepository.deleteByProcessedAtBefore(cutoff);
        log.info("ProcessedEventsCleanup: deleted {} records older than {} days", deleted, RETENTION_DAYS);
    }
}
