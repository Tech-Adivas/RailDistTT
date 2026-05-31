package com.railway.platform.schedule.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted record of the latest computed schedule for a timetable.
 *
 * <p>The schedule-service is conceptually stateless for computation but persists computed results
 * so the query-service projector can rebuild the read model without re-triggering computation.
 * The JSON payload stores the full ScheduleComputedEvent payload for replay.
 */
@Entity
@Table(
    name = "computed_schedules",
    indexes = {
      @Index(name = "idx_computed_timetable", columnList = "timetable_id"),
      @Index(name = "idx_computed_line", columnList = "line_id"),
      @Index(name = "idx_computed_effective", columnList = "effective_date")
    })
public class ComputedScheduleEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "timetable_id", nullable = false)
  private String timetableId;

  @Column(name = "line_id", nullable = false)
  private String lineId;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @Column(name = "expiry_date")
  private LocalDate expiryDate;

  @Column(name = "triggering_event_id", nullable = false)
  private String triggeringEventId;

  /** Full JSON payload of the ScheduleComputedEvent for downstream replay. */
  @Column(name = "schedule_payload", columnDefinition = "TEXT", nullable = false)
  private String schedulePayload;

  @Column(name = "computed_at", nullable = false)
  private Instant computedAt;

  public ComputedScheduleEntity() {}

  // ── Accessors ─────────────────────────────────────────────────────────────

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public String getTimetableId() { return timetableId; }
  public void setTimetableId(String timetableId) { this.timetableId = timetableId; }
  public String getLineId() { return lineId; }
  public void setLineId(String lineId) { this.lineId = lineId; }
  public LocalDate getEffectiveDate() { return effectiveDate; }
  public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
  public LocalDate getExpiryDate() { return expiryDate; }
  public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
  public String getTriggeringEventId() { return triggeringEventId; }
  public void setTriggeringEventId(String triggeringEventId) { this.triggeringEventId = triggeringEventId; }
  public String getSchedulePayload() { return schedulePayload; }
  public void setSchedulePayload(String schedulePayload) { this.schedulePayload = schedulePayload; }
  public Instant getComputedAt() { return computedAt; }
  public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
}
