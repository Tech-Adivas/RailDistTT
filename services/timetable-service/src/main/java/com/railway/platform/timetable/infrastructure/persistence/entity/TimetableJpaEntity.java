package com.railway.platform.timetable.infrastructure.persistence.entity;

import com.railway.platform.timetable.domain.valueobject.TimetableStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the timetable aggregate. Kept deliberately anemic — domain logic lives in
 * {@link com.railway.platform.timetable.domain.model.Timetable}.
 *
 * <p>The {@code @Version} field provides optimistic locking: if two concurrent requests load
 * the same version and one commits first, the second will receive an
 * {@link org.springframework.dao.OptimisticLockingFailureException} which the command handler
 * translates to a 409 response.
 */
@Entity
@Table(
    name = "timetables",
    indexes = {
      @Index(name = "idx_timetable_line_id", columnList = "line_id"),
      @Index(name = "idx_timetable_status", columnList = "status"),
      @Index(name = "idx_timetable_effective_date", columnList = "effective_date")
    })
public class TimetableJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "line_id", nullable = false)
  private String lineId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private TimetableStatus status;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description", columnDefinition = "TEXT")
  private String description;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @Column(name = "expiry_date")
  private LocalDate expiryDate;

  @Column(name = "author_id", nullable = false)
  private String authorId;

  @Column(name = "reviewer_id")
  private String reviewerId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Optimistic locking version. JPA increments this on every UPDATE. A concurrent write
   * attempting to update a stale version will fail with OptimisticLockingFailureException.
   */
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @PrePersist
  private void prePersist() {
    createdAt = Instant.now();
    updatedAt = Instant.now();
  }

  @PreUpdate
  private void preUpdate() {
    updatedAt = Instant.now();
  }

  // ── Accessors ────────────────────────────────────────────────────────────────
  // (Getters and setters — kept for JPA; record semantics live in the domain model.)

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public String getLineId() { return lineId; }
  public void setLineId(String lineId) { this.lineId = lineId; }
  public TimetableStatus getStatus() { return status; }
  public void setStatus(TimetableStatus status) { this.status = status; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public LocalDate getEffectiveDate() { return effectiveDate; }
  public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
  public LocalDate getExpiryDate() { return expiryDate; }
  public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
  public String getAuthorId() { return authorId; }
  public void setAuthorId(String authorId) { this.authorId = authorId; }
  public String getReviewerId() { return reviewerId; }
  public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getVersion() { return version; }
  public void setVersion(long version) { this.version = version; }
}
