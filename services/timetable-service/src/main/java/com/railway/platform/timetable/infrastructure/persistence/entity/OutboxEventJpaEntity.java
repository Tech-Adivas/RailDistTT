package com.railway.platform.timetable.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the Transactional Outbox table.
 *
 * <p>Each row represents a domain event that Debezium will relay to Kafka after the containing
 * transaction commits. The schema maps to the Debezium outbox event router configuration in
 * infra/debezium/application.properties.
 *
 * <p>Rows are NEVER deleted by the application — Debezium manages its own offset and only needs
 * the row to be visible in the WAL. The Debezium outbox router reads and marks events as
 * processed. In practice a separate cleanup job can remove rows older than 7 days.
 *
 * <p>Column naming must match debezium.source.transforms.outbox.table.field.* in
 * infra/debezium/application.properties exactly.
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
      @Index(name = "idx_outbox_aggregate_id", columnList = "aggregate_id"),
      @Index(name = "idx_outbox_created_at", columnList = "created_at")
    })
public class OutboxEventJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** Debezium routes to Kafka topic based on this field. Value: "timetable" → topic "railway.timetable.changed". */
  @Column(name = "aggregate_type", nullable = false, length = 50)
  private String aggregateType;

  /** The aggregate root ID — used as the Kafka message key for partition ordering. */
  @Column(name = "aggregate_id", nullable = false)
  private String aggregateId;

  /** Event type name — carried as a Kafka record header. */
  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  /** Avro-serialised JSON payload of the event. */
  @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
  private String payload;

  /** Correlation ID — propagated as a Kafka record header by Debezium. */
  @Column(name = "correlation_id", nullable = false)
  private String correlationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  private void prePersist() {
    createdAt = Instant.now();
  }

  // ── Accessors ─────────────────────────────────────────────────────────────

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public String getAggregateType() { return aggregateType; }
  public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
  public String getAggregateId() { return aggregateId; }
  public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
  public String getEventType() { return eventType; }
  public void setEventType(String eventType) { this.eventType = eventType; }
  public String getPayload() { return payload; }
  public void setPayload(String payload) { this.payload = payload; }
  public String getCorrelationId() { return correlationId; }
  public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
  public Instant getCreatedAt() { return createdAt; }
}
