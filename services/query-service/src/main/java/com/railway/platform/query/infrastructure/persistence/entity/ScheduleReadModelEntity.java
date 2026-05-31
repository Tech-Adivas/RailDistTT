package com.railway.platform.query.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model projection of a computed schedule.
 *
 * <p>Rebuilt from ScheduleComputedEvents by the ScheduleProjector. The
 * {@code scheduleData} column stores the full Avro-event JSON so downstream
 * callers can receive the complete stop-time list without re-joining tables.
 */
@Entity
@Table(
    name = "schedule_read_model",
    indexes = {
      @Index(name = "idx_srm_timetable",  columnList = "timetable_id"),
      @Index(name = "idx_srm_line",        columnList = "line_id"),
      @Index(name = "idx_srm_effective",   columnList = "effective_date")
    })
public class ScheduleReadModelEntity {

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

  /** Full ScheduleComputedEvent payload serialised as JSON. */
  @Column(name = "schedule_data", columnDefinition = "TEXT", nullable = false)
  private String scheduleData;

  @Column(name = "triggering_event_id", nullable = false)
  private String triggeringEventId;

  @Column(name = "computed_at", nullable = false)
  private Instant computedAt;

  // ── Accessors ─────────────────────────────────────────────────────────────

  public UUID getId()                     { return id; }
  public void setId(UUID id)              { this.id = id; }

  public String getTimetableId()          { return timetableId; }
  public void setTimetableId(String v)    { this.timetableId = v; }

  public String getLineId()               { return lineId; }
  public void setLineId(String v)         { this.lineId = v; }

  public LocalDate getEffectiveDate()     { return effectiveDate; }
  public void setEffectiveDate(LocalDate v) { this.effectiveDate = v; }

  public LocalDate getExpiryDate()        { return expiryDate; }
  public void setExpiryDate(LocalDate v)  { this.expiryDate = v; }

  public String getScheduleData()         { return scheduleData; }
  public void setScheduleData(String v)   { this.scheduleData = v; }

  public String getTriggeringEventId()    { return triggeringEventId; }
  public void setTriggeringEventId(String v) { this.triggeringEventId = v; }

  public Instant getComputedAt()          { return computedAt; }
  public void setComputedAt(Instant v)    { this.computedAt = v; }
}
