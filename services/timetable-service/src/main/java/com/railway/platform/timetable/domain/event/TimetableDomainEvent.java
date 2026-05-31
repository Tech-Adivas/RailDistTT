package com.railway.platform.timetable.domain.event;

import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a domain event raised by the Timetable aggregate.
 *
 * <p>These events are collected during command execution and written to the Transactional Outbox
 * by the command handler. They are NOT the Avro events published to Kafka — those are built by
 * the OutboxEventMapper from these domain events.
 *
 * <p>Keeping domain events separate from Avro events means the domain layer has no dependency on
 * the messaging infrastructure.
 */
public record TimetableDomainEvent(
    String eventId,
    String timetableId,
    String lineId,
    EventType eventType,
    TimetableStatus previousStatus,
    TimetableStatus newStatus,
    String actor,
    String payload,      // JSON payload (built by OutboxEventMapper)
    Instant occurredAt,
    String justification // non-null only for EMERGENCY_ACTIVATED
) {

  public enum EventType {
    CREATED,
    UPDATED,
    SUBMITTED_FOR_REVIEW,
    APPROVED,
    REJECTED,
    CHANGES_REQUESTED,
    ACTIVATED,
    EMERGENCY_ACTIVATED,
    SUPERSEDED,
    CANCELLED
  }

  // ── Factory methods (one per state transition) ───────────────────────────────

  public static TimetableDomainEvent created(Timetable t, String actor) {
    return build(t, EventType.CREATED, null, TimetableStatus.DRAFT, actor, null);
  }

  public static TimetableDomainEvent updated(Timetable t, String actor) {
    return build(t, EventType.UPDATED, TimetableStatus.DRAFT, TimetableStatus.DRAFT, actor, null);
  }

  public static TimetableDomainEvent submittedForReview(Timetable t, String actor) {
    return build(t, EventType.SUBMITTED_FOR_REVIEW, TimetableStatus.DRAFT, TimetableStatus.PENDING_REVIEW, actor, null);
  }

  public static TimetableDomainEvent approved(Timetable t, String actor) {
    return build(t, EventType.APPROVED, TimetableStatus.PENDING_REVIEW, TimetableStatus.APPROVED, actor, null);
  }

  public static TimetableDomainEvent rejected(Timetable t, String actor, String reason) {
    return build(t, EventType.REJECTED, TimetableStatus.PENDING_REVIEW, TimetableStatus.REJECTED, actor, reason);
  }

  public static TimetableDomainEvent changesRequested(Timetable t, String actor) {
    return build(t, EventType.CHANGES_REQUESTED, TimetableStatus.PENDING_REVIEW, TimetableStatus.DRAFT, actor, null);
  }

  public static TimetableDomainEvent activated(Timetable t, String actor) {
    return build(t, EventType.ACTIVATED, TimetableStatus.APPROVED, TimetableStatus.ACTIVE, actor, null);
  }

  public static TimetableDomainEvent emergencyActivated(Timetable t, String actor, String justification) {
    return build(t, EventType.EMERGENCY_ACTIVATED, t.getStatus(), TimetableStatus.EMERGENCY_ACTIVE, actor, justification);
  }

  public static TimetableDomainEvent superseded(Timetable t, String actor) {
    return build(t, EventType.SUPERSEDED, t.getStatus(), TimetableStatus.SUPERSEDED, actor, null);
  }

  public static TimetableDomainEvent cancelled(Timetable t, String actor) {
    return build(t, EventType.CANCELLED, t.getStatus(), TimetableStatus.CANCELLED, actor, null);
  }

  private static TimetableDomainEvent build(
      Timetable t,
      EventType type,
      TimetableStatus prev,
      TimetableStatus next,
      String actor,
      String payload) {
    return new TimetableDomainEvent(
        UUID.randomUUID().toString(),
        t.getId().toString(),
        t.getLineId().toString(),
        type,
        prev,
        next,
        actor,
        payload,
        Instant.now(),
        payload != null && type == EventType.EMERGENCY_ACTIVATED ? payload : null);
  }
}
