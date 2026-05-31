package com.railway.platform.timetable.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the immutable audit log.
 *
 * <p>Every state-changing operation on a timetable is recorded here. Rows are append-only —
 * no UPDATE or DELETE is ever issued on this table. The table has no foreign key to timetables
 * so that audit records survive even if a timetable is hard-deleted (which should not happen
 * in this system, but the audit log must be resilient to schema changes).
 *
 * <p>This satisfies the "immutable, append-only audit trail" non-functional requirement.
 */
@Entity
@Table(
    name = "audit_log",
    indexes = {
      @Index(name = "idx_audit_aggregate_id", columnList = "aggregate_id"),
      @Index(name = "idx_audit_actor", columnList = "actor"),
      @Index(name = "idx_audit_occurred_at", columnList = "occurred_at")
    })
public class AuditLogJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "aggregate_type", nullable = false, length = 50)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private String aggregateId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  /** Status before this event (null for CREATED events). */
  @Column(name = "previous_status", length = 30)
  private String previousStatus;

  @Column(name = "new_status", nullable = false, length = 30)
  private String newStatus;

  /** The authenticated user who triggered this operation. */
  @Column(name = "actor", nullable = false)
  private String actor;

  /** Correlation ID of the originating HTTP request. */
  @Column(name = "correlation_id", nullable = false)
  private String correlationId;

  /** JSON representation of the full aggregate state at this point in time. */
  @Column(name = "snapshot", columnDefinition = "TEXT")
  private String snapshot;

  /**
   * Mandatory justification for emergency operations; null for normal operations.
   * Stored here for the post-hoc review workflow.
   */
  @Column(name = "justification", columnDefinition = "TEXT")
  private String justification;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  // ── Accessors ─────────────────────────────────────────────────────────────

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public String getAggregateType() { return aggregateType; }
  public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
  public String getAggregateId() { return aggregateId; }
  public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
  public String getEventType() { return eventType; }
  public void setEventType(String eventType) { this.eventType = eventType; }
  public String getPreviousStatus() { return previousStatus; }
  public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }
  public String getNewStatus() { return newStatus; }
  public void setNewStatus(String newStatus) { this.newStatus = newStatus; }
  public String getActor() { return actor; }
  public void setActor(String actor) { this.actor = actor; }
  public String getCorrelationId() { return correlationId; }
  public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
  public String getSnapshot() { return snapshot; }
  public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
  public String getJustification() { return justification; }
  public void setJustification(String justification) { this.justification = justification; }
  public Instant getOccurredAt() { return occurredAt; }
  public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
