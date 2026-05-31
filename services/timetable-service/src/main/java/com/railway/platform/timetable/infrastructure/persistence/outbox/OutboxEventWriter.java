package com.railway.platform.timetable.infrastructure.persistence.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.timetable.domain.event.TimetableDomainEvent;
import com.railway.platform.timetable.infrastructure.persistence.entity.OutboxEventJpaEntity;
import com.railway.platform.timetable.infrastructure.persistence.repository.OutboxEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Writes domain events into the Transactional Outbox table.
 *
 * <p>This component is called within the same database transaction as the aggregate save.
 * Debezium reads committed outbox rows via CDC and publishes them to Kafka. This guarantees
 * that a domain event is published if and only if the aggregate state change committed.
 *
 * <p>The payload is serialised to JSON here. In a full production setup the payload would be
 * Avro-serialised; for the outbox pattern JSON is acceptable because the Debezium outbox event
 * router can forward the payload as-is, and the Avro schema is enforced at the consumer side.
 */
@Component
public class OutboxEventWriter {

  private static final Logger log = LoggerFactory.getLogger(OutboxEventWriter.class);
  private static final String AGGREGATE_TYPE = "timetable";

  private final OutboxEventJpaRepository repository;
  private final ObjectMapper objectMapper;

  public OutboxEventWriter(OutboxEventJpaRepository repository) {
    this.repository = repository;
    this.objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  /**
   * Persists outbox rows for all domain events raised during a command.
   * Must be called inside an active transaction — the caller (TimetableCommandHandler) ensures this.
   *
   * @param events Domain events raised by the aggregate during a command.
   */
  public void write(List<TimetableDomainEvent> events) {
    for (TimetableDomainEvent event : events) {
      var entity = new OutboxEventJpaEntity();
      entity.setId(UUID.fromString(event.eventId()));
      entity.setAggregateType(AGGREGATE_TYPE);
      entity.setAggregateId(event.timetableId());
      entity.setEventType(event.eventType().name());
      entity.setPayload(serialise(event));
      // Propagate the correlation ID so Debezium copies it to the Kafka record header.
      entity.setCorrelationId(correlationId());
      repository.save(entity);

      log.debug("Outbox event written [eventId={}] [type={}] [aggregateId={}]",
          event.eventId(), event.eventType(), event.timetableId());
    }
  }

  private String serialise(TimetableDomainEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException ex) {
      // If serialisation fails, the transaction must roll back — a partial outbox row
      // with no payload would be worse than no row at all.
      throw new RuntimeException("Failed to serialise domain event to JSON: " + event.eventId(), ex);
    }
  }

  private String correlationId() {
    String id = CorrelationIdHolder.get();
    return id != null ? id : "unknown";
  }
}
