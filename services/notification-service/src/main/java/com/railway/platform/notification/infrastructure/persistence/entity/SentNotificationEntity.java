package com.railway.platform.notification.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of every notification delivery attempt.
 *
 * <p>One row per (eventId, recipientId, channel) — so a single NotificationRequestEvent that
 * targets 3 recipients via 2 channels produces 6 rows. This allows auditing exactly which
 * recipients received (or failed to receive) which notifications.
 */
@Entity
@Table(
    name = "sent_notifications",
    indexes = {
      @Index(name = "idx_sn_event_id",    columnList = "event_id"),
      @Index(name = "idx_sn_recipient",   columnList = "recipient_id"),
      @Index(name = "idx_sn_sent_at",     columnList = "sent_at")
    })
public class SentNotificationEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "event_id", nullable = false)
  private String eventId;

  @Column(name = "recipient_id", nullable = false, length = 255)
  private String recipientId;

  @Column(name = "channel", nullable = false, length = 20)
  private String channel;

  @Column(name = "notification_type", nullable = false, length = 50)
  private String notificationType;

  @Column(name = "title", nullable = false, length = 500)
  private String title;

  @Column(name = "body", columnDefinition = "TEXT", nullable = false)
  private String body;

  @Column(name = "is_emergency", nullable = false)
  private boolean emergency;

  @Column(name = "sent_at", nullable = false)
  private Instant sentAt;

  @Column(name = "correlation_id", length = 255)
  private String correlationId;

  public UUID getId()                    { return id; }
  public void setId(UUID v)              { this.id = v; }
  public String getEventId()             { return eventId; }
  public void setEventId(String v)       { this.eventId = v; }
  public String getRecipientId()         { return recipientId; }
  public void setRecipientId(String v)   { this.recipientId = v; }
  public String getChannel()             { return channel; }
  public void setChannel(String v)       { this.channel = v; }
  public String getNotificationType()    { return notificationType; }
  public void setNotificationType(String v) { this.notificationType = v; }
  public String getTitle()               { return title; }
  public void setTitle(String v)         { this.title = v; }
  public String getBody()                { return body; }
  public void setBody(String v)          { this.body = v; }
  public boolean isEmergency()           { return emergency; }
  public void setEmergency(boolean v)    { this.emergency = v; }
  public Instant getSentAt()             { return sentAt; }
  public void setSentAt(Instant v)       { this.sentAt = v; }
  public String getCorrelationId()       { return correlationId; }
  public void setCorrelationId(String v) { this.correlationId = v; }
}
