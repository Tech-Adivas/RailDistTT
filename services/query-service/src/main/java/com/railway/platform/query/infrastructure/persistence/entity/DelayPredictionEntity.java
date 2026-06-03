package com.railway.platform.query.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code delay_predictions} table.
 *
 * <p>One row per {@code DelayPredictionEvent} received from the Python AI Prediction Service.
 * The {@code event_id} column carries a UNIQUE constraint that serves as the idempotency guard:
 * a duplicate insert throws {@link org.springframework.dao.DataIntegrityViolationException}
 * which the projector catches and acknowledges silently.
 */
@Entity
@Table(name = "delay_predictions")
public class DelayPredictionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID predictionId;

  @Column(nullable = false)
  private String routeId;

  private String trainId;

  @Column(nullable = false)
  private int predictedDelayMinutes;

  @Column(nullable = false)
  private double confidenceScore;

  @Column(nullable = false)
  private String modelVersion;

  @Column(nullable = false)
  private Instant predictedAt;

  @Column(nullable = false, unique = true)
  private String eventId;

  @Column(nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  public DelayPredictionEntity() {}

  public DelayPredictionEntity(
      String routeId,
      String trainId,
      int predictedDelayMinutes,
      double confidenceScore,
      String modelVersion,
      Instant predictedAt,
      String eventId) {
    this.routeId = routeId;
    this.trainId = trainId;
    this.predictedDelayMinutes = predictedDelayMinutes;
    this.confidenceScore = confidenceScore;
    this.modelVersion = modelVersion;
    this.predictedAt = predictedAt;
    this.eventId = eventId;
  }

  // ── Getters ──────────────────────────────────────────────────────────────────

  public UUID getPredictionId() {
    return predictionId;
  }

  public String getRouteId() {
    return routeId;
  }

  public String getTrainId() {
    return trainId;
  }

  public int getPredictedDelayMinutes() {
    return predictedDelayMinutes;
  }

  public double getConfidenceScore() {
    return confidenceScore;
  }

  public String getModelVersion() {
    return modelVersion;
  }

  public Instant getPredictedAt() {
    return predictedAt;
  }

  public String getEventId() {
    return eventId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  // ── Setters ──────────────────────────────────────────────────────────────────

  public void setPredictionId(UUID predictionId) {
    this.predictionId = predictionId;
  }

  public void setRouteId(String routeId) {
    this.routeId = routeId;
  }

  public void setTrainId(String trainId) {
    this.trainId = trainId;
  }

  public void setPredictedDelayMinutes(int predictedDelayMinutes) {
    this.predictedDelayMinutes = predictedDelayMinutes;
  }

  public void setConfidenceScore(double confidenceScore) {
    this.confidenceScore = confidenceScore;
  }

  public void setModelVersion(String modelVersion) {
    this.modelVersion = modelVersion;
  }

  public void setPredictedAt(Instant predictedAt) {
    this.predictedAt = predictedAt;
  }

  public void setEventId(String eventId) {
    this.eventId = eventId;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
