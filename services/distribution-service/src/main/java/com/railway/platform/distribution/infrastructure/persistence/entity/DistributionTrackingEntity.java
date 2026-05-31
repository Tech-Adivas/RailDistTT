package com.railway.platform.distribution.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-channel distribution tracking record.
 *
 * <p>Written atomically with the processed_events row so the saga state is always
 * consistent: if the Kafka transaction aborts, both rows are rolled back.
 */
@Entity
@Table(
    name = "distribution_tracking",
    indexes = {
      @Index(name = "idx_dt_timetable", columnList = "timetable_id"),
      @Index(name = "idx_dt_schedule_event", columnList = "schedule_event_id")
    })
public class DistributionTrackingEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "timetable_id", nullable = false)
  private String timetableId;

  @Column(name = "schedule_event_id", nullable = false)
  private String scheduleEventId;

  @Column(name = "channel", nullable = false, length = 50)
  private String channel;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;

  @Column(name = "is_emergency", nullable = false)
  private boolean emergency;

  @Column(name = "distributed_at", nullable = false)
  private Instant distributedAt;

  public UUID getId()                  { return id; }
  public void setId(UUID v)            { this.id = v; }
  public String getTimetableId()       { return timetableId; }
  public void setTimetableId(String v) { this.timetableId = v; }
  public String getScheduleEventId()   { return scheduleEventId; }
  public void setScheduleEventId(String v) { this.scheduleEventId = v; }
  public String getChannel()           { return channel; }
  public void setChannel(String v)     { this.channel = v; }
  public String getStatus()            { return status; }
  public void setStatus(String v)      { this.status = v; }
  public String getFailureReason()     { return failureReason; }
  public void setFailureReason(String v) { this.failureReason = v; }
  public boolean isEmergency()         { return emergency; }
  public void setEmergency(boolean v)  { this.emergency = v; }
  public Instant getDistributedAt()    { return distributedAt; }
  public void setDistributedAt(Instant v) { this.distributedAt = v; }
}
