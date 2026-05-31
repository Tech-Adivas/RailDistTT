package com.railway.platform.timetable.infrastructure.persistence.outbox;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.timetable.domain.event.TimetableDomainEvent;
import com.railway.platform.timetable.infrastructure.persistence.entity.AuditLogJpaEntity;
import com.railway.platform.timetable.infrastructure.persistence.repository.AuditLogJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Writes to the immutable, append-only audit log within the same database transaction
 * as the aggregate state change and outbox row.
 *
 * <p>The audit log captures: what changed, who changed it, when, and (for emergency overrides)
 * why. It is the authoritative record for compliance and post-incident review.
 *
 * <p>Rows are never updated or deleted. If regulatory retention requires pruning,
 * archive to cold storage first.
 */
@Component
public class AuditLogWriter {

  private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);

  private final AuditLogJpaRepository repository;

  public AuditLogWriter(AuditLogJpaRepository repository) {
    this.repository = repository;
  }

  /**
   * Appends one audit log entry per domain event.
   * Must be called inside an active transaction.
   */
  public void write(List<TimetableDomainEvent> events, String actor) {
    for (TimetableDomainEvent event : events) {
      var entry = new AuditLogJpaEntity();
      entry.setId(UUID.randomUUID());
      entry.setAggregateType("timetable");
      entry.setAggregateId(event.timetableId());
      entry.setEventType(event.eventType().name());
      entry.setPreviousStatus(event.previousStatus() != null ? event.previousStatus().name() : null);
      entry.setNewStatus(event.newStatus().name());
      entry.setActor(actor);
      entry.setCorrelationId(correlationId());
      entry.setJustification(event.justification());
      entry.setOccurredAt(event.occurredAt());

      repository.save(entry);

      if (event.eventType() == TimetableDomainEvent.EventType.EMERGENCY_ACTIVATED) {
        // Emergency activations logged at WARN so they surface in the alert system.
        log.warn("AUDIT [EMERGENCY_ACTIVATED] [timetable={}] [actor={}] [justification={}]",
            event.timetableId(), actor, event.justification());
      } else {
        log.info("AUDIT [{}] [timetable={}] [actor={}] [{}→{}]",
            event.eventType(), event.timetableId(), actor,
            event.previousStatus(), event.newStatus());
      }
    }
  }

  private String correlationId() {
    String id = CorrelationIdHolder.get();
    return id != null ? id : "unknown";
  }
}
