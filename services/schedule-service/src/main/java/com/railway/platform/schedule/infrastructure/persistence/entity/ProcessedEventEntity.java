package com.railway.platform.schedule.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Records eventIds that have been successfully processed.
 *
 * <p>Used for idempotency: before processing an event the consumer checks whether its eventId is
 * already in this table. If yes, the event is skipped (no-op). If no, the event is processed and
 * the eventId is inserted in the same transaction as the schedule write.
 *
 * <p>The UNIQUE constraint on event_id makes a duplicate insert fail, which serves as the race
 * condition guard under concurrent consumer instances.
 */
@Entity
@Table(
    name = "processed_events",
    indexes = {@Index(name = "idx_processed_event_id", columnList = "event_id")})
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

  public String getEventId() { return eventId; }
  public String getEventType() { return eventType; }
  public Instant getProcessedAt() { return processedAt; }
}
