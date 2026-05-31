package com.railway.platform.schedule.service;

import com.railway.platform.events.ScheduleComputedEvent;
import com.railway.platform.events.ScheduledService;
import com.railway.platform.events.StopTime;
import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.TimetableChangedEvent;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Computes effective schedules from a TimetableChangedEvent.
 *
 * <p>In a real implementation this would load the full timetable data from a read store, resolve
 * recurring patterns, apply maintenance windows, and produce per-service stop times. The current
 * implementation provides the correct structure and integration points — the computation algorithm
 * is a placeholder to be filled with real business logic in Phase 2 completion.
 *
 * <p>This class is intentionally free of Kafka and persistence dependencies — those are the
 * responsibility of the consumer and producer layers.
 */
@Service
public class ScheduleComputationService {

  private static final Logger log = LoggerFactory.getLogger(ScheduleComputationService.class);

  /**
   * Computes the effective schedule from a timetable change event.
   *
   * <p>Computation steps:
   * <ol>
   *   <li>Determine the affected date range (effectiveDate → expiryDate).
   *   <li>Load the timetable service patterns (placeholder: returns a stub service list).
   *   <li>Apply any active maintenance windows for affected track segments.
   *   <li>Build per-service stop-time lists.
   *   <li>Return a ScheduleComputedEvent.
   * </ol>
   *
   * @param event  The triggering TimetableChangedEvent.
   * @param correlationId Correlation ID propagated from the Kafka record header.
   * @return A fully populated ScheduleComputedEvent ready to publish to Kafka.
   */
  @Timed(value = "schedule.computation.duration", description = "Time to compute effective schedule from timetable change")
  public ScheduleComputedEvent compute(TimetableChangedEvent event, String correlationId) {
    log.info("Computing schedule [timetableId={}] [changeType={}] [correlationId={}]",
        event.getTimetableId(), event.getChangeType(), correlationId);

    var metadata = EventMetadata.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setEventType(ScheduleComputedEvent.class.getName())
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId(correlationId)
        .setActor("system:schedule-service")
        .setSchemaVersion(1)
        .build();

    // TODO: Replace with real schedule computation from timetable data store.
    // This stub returns an empty services list, which is valid for CANCELLED/SUPERSEDED events.
    List<ScheduledService> services = computeServices(event);

    return ScheduleComputedEvent.newBuilder()
        .setMetadata(metadata)
        .setTimetableId(event.getTimetableId())
        .setLineId(event.getLineId())
        .setEffectiveDate(event.getEffectiveDate())
        .setExpiryDate(event.getExpiryDate())
        .setServices(services)
        .setTriggeringEventId(event.getMetadata().getEventId())
        .build();
  }

  /**
   * Builds the list of scheduled train services.
   *
   * <p>TODO: Implement using real timetable pattern data. This stub returns an empty list for
   * CANCELLED/SUPERSEDED events and a placeholder for APPROVED/ACTIVATED events.
   */
  private List<ScheduledService> computeServices(TimetableChangedEvent event) {
    return switch (event.getChangeType()) {
      case CANCELLED, SUPERSEDED, REJECTED -> List.of(); // No services for terminal states.
      default -> List.of(); // TODO: Load real services from timetable data store.
    };
  }
}
