package com.railway.platform.query.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Records event IDs that have been successfully projected into the read model.
 *
 * <p>The UNIQUE constraint on event_id is the idempotency guard: a duplicate
 * insert fails with a constraint violation, and the projector catches it to
 * safely skip re-projection.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEventEntity {

  @Id
  @Column(name = "event_id", nullable = false, updatable = false, unique = true)
  private String eventId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @Column(name = "processed_at", nullable = false, updatable = false)
  private Instant processedAt;

  public ProcessedEventEntity() {}

  public ProcessedEventEntity(String eventId, String eventType) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.processedAt = Instant.now();
  }

  public String getEventId()   { return eventId; }
  public String getEventType() { return eventType; }
  public Instant getProcessedAt() { return processedAt; }
}
