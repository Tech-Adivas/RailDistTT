package com.railway.platform.events;

import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.avro.Schema;

/**
 * Avro-generated POJO for EventMetadata.
 *
 * <p>Common metadata carried by every platform event.
 * Consumers use {@code eventId} for idempotency deduplication.
 *
 * <p>This class is generated from {@code shared/events/src/main/avro/common.avsc}.
 * Run {@code mvn -pl shared/events -am clean generate-sources} to regenerate.
 */
public class EventMetadata extends SpecificRecordBase implements SpecificRecord {

  private static final long serialVersionUID = 1L;

  private String eventId;
  private String eventType;
  private long occurredAt;
  private String correlationId;
  private String actor;
  private int schemaVersion = 1;

  public EventMetadata() {}

  public EventMetadata(
      String eventId,
      String eventType,
      long occurredAt,
      String correlationId,
      String actor,
      int schemaVersion) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.occurredAt = occurredAt;
    this.correlationId = correlationId;
    this.actor = actor;
    this.schemaVersion = schemaVersion;
  }

  public static final Schema SCHEMA$ = new Schema.Parser().parse(
      "{"
    + "\"namespace\":\"com.railway.platform.events\","
    + "\"name\":\"EventMetadata\","
    + "\"type\":\"record\","
    + "\"fields\":["
    + "  {\"name\":\"eventId\",\"type\":\"string\"},"
    + "  {\"name\":\"eventType\",\"type\":\"string\"},"
    + "  {\"name\":\"occurredAt\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},"
    + "  {\"name\":\"correlationId\",\"type\":\"string\"},"
    + "  {\"name\":\"actor\",\"type\":[\"null\",\"string\"],\"default\":null},"
    + "  {\"name\":\"schemaVersion\",\"type\":\"int\",\"default\":1}"
    + "]}");

  @Override
  public Schema getSchema() {
    return SCHEMA$;
  }

  @Override
  public Object get(int field$) {
    return switch (field$) {
      case 0 -> eventId;
      case 1 -> eventType;
      case 2 -> occurredAt;
      case 3 -> correlationId;
      case 4 -> actor;
      case 5 -> schemaVersion;
      default -> throw new org.apache.avro.AvroRuntimeException("Bad index: " + field$);
    };
  }

  @Override
  public void put(int field$, Object value$) {
    switch (field$) {
      case 0 -> this.eventId = value$ != null ? value$.toString() : null;
      case 1 -> this.eventType = value$ != null ? value$.toString() : null;
      case 2 -> this.occurredAt = (Long) value$;
      case 3 -> this.correlationId = value$ != null ? value$.toString() : null;
      case 4 -> this.actor = value$ != null ? value$.toString() : null;
      case 5 -> this.schemaVersion = (Integer) value$;
      default -> throw new org.apache.avro.AvroRuntimeException("Bad index: " + field$);
    }
  }

  // ── Getters ──────────────────────────────────────────────────────────────────

  public String getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public long getOccurredAt() {
    return occurredAt;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public String getActor() {
    return actor;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  // ── Setters ──────────────────────────────────────────────────────────────────

  public void setEventId(String eventId) {
    this.eventId = eventId;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public void setOccurredAt(long occurredAt) {
    this.occurredAt = occurredAt;
  }

  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }

  public void setActor(String actor) {
    this.actor = actor;
  }

  public void setSchemaVersion(int schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  @Override
  public String toString() {
    return "EventMetadata{"
        + "eventId='" + eventId + '\''
        + ", eventType='" + eventType + '\''
        + ", occurredAt=" + occurredAt
        + ", correlationId='" + correlationId + '\''
        + ", actor='" + actor + '\''
        + ", schemaVersion=" + schemaVersion
        + '}';
  }
}
