package com.railway.platform.events;

import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.avro.Schema;

/**
 * Avro-generated POJO for DelayPredictionEvent.
 *
 * <p>Emitted by the Python AI Prediction Service after computing a delay prediction
 * from a ScheduleComputedEvent. Published to {@code railway.delay.prediction.computed}.
 *
 * <p>This class is generated from {@code shared/events/src/main/avro/delay-prediction-event.avsc}.
 * Run {@code mvn -pl shared/events -am clean generate-sources} to regenerate.
 */
public class DelayPredictionEvent extends SpecificRecordBase implements SpecificRecord {

  private static final long serialVersionUID = 1L;

  private EventMetadata metadata;
  private String routeId;
  private String trainId;
  private int predictedDelayMinutes;
  private float confidenceScore;
  private String modelVersion;
  private String triggeringScheduleEventId;

  public DelayPredictionEvent() {}

  public DelayPredictionEvent(
      EventMetadata metadata,
      String routeId,
      String trainId,
      int predictedDelayMinutes,
      float confidenceScore,
      String modelVersion,
      String triggeringScheduleEventId) {
    this.metadata = metadata;
    this.routeId = routeId;
    this.trainId = trainId;
    this.predictedDelayMinutes = predictedDelayMinutes;
    this.confidenceScore = confidenceScore;
    this.modelVersion = modelVersion;
    this.triggeringScheduleEventId = triggeringScheduleEventId;
  }

  // ── Avro schema ──────────────────────────────────────────────────────────────

  public static final Schema SCHEMA$ = new Schema.Parser().parse(
      "{"
    + "\"namespace\":\"com.railway.platform.events\","
    + "\"name\":\"DelayPredictionEvent\","
    + "\"type\":\"record\","
    + "\"fields\":["
    + "  {\"name\":\"metadata\",\"type\":\"com.railway.platform.events.EventMetadata\"},"
    + "  {\"name\":\"routeId\",\"type\":\"string\"},"
    + "  {\"name\":\"trainId\",\"type\":[\"null\",\"string\"],\"default\":null},"
    + "  {\"name\":\"predictedDelayMinutes\",\"type\":\"int\"},"
    + "  {\"name\":\"confidenceScore\",\"type\":\"float\"},"
    + "  {\"name\":\"modelVersion\",\"type\":\"string\"},"
    + "  {\"name\":\"triggeringScheduleEventId\",\"type\":\"string\"}"
    + "]}");

  @Override
  public Schema getSchema() {
    return SCHEMA$;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object get(int field$) {
    return switch (field$) {
      case 0 -> metadata;
      case 1 -> routeId;
      case 2 -> trainId;
      case 3 -> predictedDelayMinutes;
      case 4 -> confidenceScore;
      case 5 -> modelVersion;
      case 6 -> triggeringScheduleEventId;
      default -> throw new org.apache.avro.AvroRuntimeException("Bad index: " + field$);
    };
  }

  @Override
  @SuppressWarnings("unchecked")
  public void put(int field$, Object value$) {
    switch (field$) {
      case 0 -> this.metadata = (EventMetadata) value$;
      case 1 -> this.routeId = value$ != null ? value$.toString() : null;
      case 2 -> this.trainId = value$ != null ? value$.toString() : null;
      case 3 -> this.predictedDelayMinutes = (Integer) value$;
      case 4 -> this.confidenceScore = (Float) value$;
      case 5 -> this.modelVersion = value$ != null ? value$.toString() : null;
      case 6 -> this.triggeringScheduleEventId = value$ != null ? value$.toString() : null;
      default -> throw new org.apache.avro.AvroRuntimeException("Bad index: " + field$);
    }
  }

  // ── Getters ──────────────────────────────────────────────────────────────────

  public EventMetadata getMetadata() {
    return metadata;
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

  public float getConfidenceScore() {
    return confidenceScore;
  }

  public String getModelVersion() {
    return modelVersion;
  }

  public String getTriggeringScheduleEventId() {
    return triggeringScheduleEventId;
  }

  // ── Setters ──────────────────────────────────────────────────────────────────

  public void setMetadata(EventMetadata metadata) {
    this.metadata = metadata;
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

  public void setConfidenceScore(float confidenceScore) {
    this.confidenceScore = confidenceScore;
  }

  public void setModelVersion(String modelVersion) {
    this.modelVersion = modelVersion;
  }

  public void setTriggeringScheduleEventId(String triggeringScheduleEventId) {
    this.triggeringScheduleEventId = triggeringScheduleEventId;
  }

  @Override
  public String toString() {
    return "DelayPredictionEvent{"
        + "routeId='" + routeId + '\''
        + ", trainId='" + trainId + '\''
        + ", predictedDelayMinutes=" + predictedDelayMinutes
        + ", confidenceScore=" + confidenceScore
        + ", modelVersion='" + modelVersion + '\''
        + '}';
  }
}
