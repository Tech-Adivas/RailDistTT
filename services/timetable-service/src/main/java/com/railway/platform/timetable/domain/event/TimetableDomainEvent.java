package com.railway.platform.timetable.domain.event;

import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain event raised by the Timetable aggregate.
 *
 * <p>Collected during command execution and written to the Transactional Outbox by the command
 * handler. These are NOT the Avro events published to Kafka; the OutboxEventWriter serialises them
 * to JSON and Debezium forwards them. Separating domain events from Avro schema keeps the domain
 * layer free of messaging infrastructure.
 *
 * @param eventId       UUID identifying this specific event instance (idempotency key).
 * @param timetableId   Aggregate root ID.
 * @param lineId        Railway line this timetable belongs to.
 * @param eventType     The kind of change.
 * @param previousStatus Status before this event (null for CREATED).
 * @param newStatus     Status after this event.
 * @param actor         User or system identity that triggered the change.
 * @param notes         Optional free-text associated with the event (rejection reason, etc.).
 * @param occurredAt    Server timestamp when the domain event was raised.
 * @param justification Mandatory for EMERGENCY_ACTIVATED; null for all other event types.
 */
public record TimetableDomainEvent(
    String eventId,
    String timetableId,
    String lineId,
    EventType eventType,
    TimetableStatus previousStatus,
    TimetableStatus newStatus,
    String actor,
    String notes,
    Instant occurredAt,
    String justification
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

  // ── Factory methods ──────────────────────────────────────────────────────────

  public static TimetableDomainEvent created(Timetable t, String actor) {
    return build(t, EventType.CREATED, null, TimetableStatus.DRAFT, actor, null, null);
  }

  public static TimetableDomainEvent updated(Timetable t, String actor) {
    return build(t, EventType.UPDATED, TimetableStatus.DRAFT, TimetableStatus.DRAFT, actor, null, null);
  }

  public static TimetableDomainEvent submittedForReview(Timetable t, String actor) {
    return build(t, EventType.SUBMITTED_FOR_REVIEW, TimetableStatus.DRAFT, TimetableStatus.PENDING_REVIEW, actor, null, null);
  }

  public static TimetableDomainEvent approved(Timetable t, String actor) {
    return build(t, EventType.APPROVED, TimetableStatus.PENDING_REVIEW, TimetableStatus.APPROVED, actor, null, null);
  }

  /** @param reason Mandatory rejection reason; stored in notes and audit log. */
  public static TimetableDomainEvent rejected(Timetable t, String actor, String reason) {
    return build(t, EventType.REJECTED, TimetableStatus.PENDING_REVIEW, TimetableStatus.REJECTED, actor, reason, null);
  }

  public static TimetableDomainEvent changesRequested(Timetable t, String actor) {
    return build(t, EventType.CHANGES_REQUESTED, TimetableStatus.PENDING_REVIEW, TimetableStatus.DRAFT, actor, null, null);
  }

  public static TimetableDomainEvent activated(Timetable t, String actor) {
    return build(t, EventType.ACTIVATED, TimetableStatus.APPROVED, TimetableStatus.ACTIVE, actor, null, null);
  }

  /** @param justification Mandatory; stored separately from notes for compliance queries. */
  public static TimetableDomainEvent emergencyActivated(Timetable t, String actor, String justification) {
    return build(t, EventType.EMERGENCY_ACTIVATED, t.getStatus(), TimetableStatus.EMERGENCY_ACTIVE, actor, null, justification);
  }

  public static TimetableDomainEvent superseded(Timetable t, String actor) {
    return build(t, EventType.SUPERSEDED, t.getStatus(), TimetableStatus.SUPERSEDED, actor, null, null);
  }

  public static TimetableDomainEvent cancelled(Timetable t, String actor) {
    return build(t, EventType.CANCELLED, t.getStatus(), TimetableStatus.CANCELLED, actor, null, null);
  }

  private static TimetableDomainEvent build(
      Timetable t,
      EventType type,
      TimetableStatus prev,
      TimetableStatus next,
      String actor,
      String notes,
      String justification) {
    return new TimetableDomainEvent(
        UUID.randomUUID().toString(),
        t.getId().toString(),
        t.getLineId().toString(),
        type,
        prev,
        next,
        actor,
        notes,
        Instant.now(),
        justification);
  }
}
