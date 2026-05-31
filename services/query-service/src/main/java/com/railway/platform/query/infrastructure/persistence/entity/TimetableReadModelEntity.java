package com.railway.platform.query.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model projection of a timetable's current state.
 *
 * <p>Rebuilt from TimetableChangedEvents by the TimetableProjector. Each UPSERT
 * overwrites the previous state so the table always reflects the latest known status.
 * The {@code lastEventId} guards idempotent replay: if the event has already been
 * applied the projector skips the update.
 */
@Entity
@Table(
    name = "timetable_read_model",
    indexes = {
      @Index(name = "idx_trm_line_id",  columnList = "line_id"),
      @Index(name = "idx_trm_status",   columnList = "status"),
      @Index(name = "idx_trm_effective", columnList = "effective_date")
    })
public class TimetableReadModelEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "line_id", nullable = false)
  private String lineId;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Column(name = "status", nullable = false, length = 50)
  private String status;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @Column(name = "expiry_date")
  private LocalDate expiryDate;

  @Column(name = "author_id", nullable = false)
  private String authorId;

  @Column(name = "reviewer_id")
  private String reviewerId;

  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "last_event_id", nullable = false)
  private String lastEventId;

  @Column(name = "last_updated_at", nullable = false)
  private Instant lastUpdatedAt;

  // ── Accessors ─────────────────────────────────────────────────────────────

  public UUID getId()                   { return id; }
  public void setId(UUID id)            { this.id = id; }

  public String getLineId()             { return lineId; }
  public void setLineId(String v)       { this.lineId = v; }

  public String getName()               { return name; }
  public void setName(String v)         { this.name = v; }

  public String getDescription()        { return description; }
  public void setDescription(String v)  { this.description = v; }

  public String getStatus()             { return status; }
  public void setStatus(String v)       { this.status = v; }

  public LocalDate getEffectiveDate()   { return effectiveDate; }
  public void setEffectiveDate(LocalDate v) { this.effectiveDate = v; }

  public LocalDate getExpiryDate()      { return expiryDate; }
  public void setExpiryDate(LocalDate v) { this.expiryDate = v; }

  public String getAuthorId()           { return authorId; }
  public void setAuthorId(String v)     { this.authorId = v; }

  public String getReviewerId()         { return reviewerId; }
  public void setReviewerId(String v)   { this.reviewerId = v; }

  public Long getVersion()              { return version; }
  public void setVersion(Long v)        { this.version = v; }

  public String getLastEventId()        { return lastEventId; }
  public void setLastEventId(String v)  { this.lastEventId = v; }

  public Instant getLastUpdatedAt()     { return lastUpdatedAt; }
  public void setLastUpdatedAt(Instant v) { this.lastUpdatedAt = v; }
}
