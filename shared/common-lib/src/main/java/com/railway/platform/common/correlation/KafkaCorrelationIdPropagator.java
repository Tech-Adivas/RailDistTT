package com.railway.platform.common.correlation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for propagating the correlation ID across Kafka record headers.
 *
 * <p>When a service produces a Kafka message it calls {@link #injectIntoHeaders} to copy the
 * current MDC correlation ID into the record headers. When a consumer receives a message, it calls
 * {@link #extractFromHeaders} to restore the correlation ID into MDC, ensuring every log line
 * produced during consumer processing shares the same correlation ID as the originating request.
 *
 * <p>Usage in a Kafka consumer:
 * <pre>{@code
 * try {
 *   String correlationId = KafkaCorrelationIdPropagator.extractFromHeaders(record.headers());
 *   CorrelationIdHolder.set(correlationId);
 *   // ... process the record
 * } finally {
 *   CorrelationIdHolder.clear();
 * }
 * }</pre>
 */
public final class KafkaCorrelationIdPropagator {

  private static final Logger log = LoggerFactory.getLogger(KafkaCorrelationIdPropagator.class);

  private KafkaCorrelationIdPropagator() {}

  /**
   * Copies the current MDC correlation ID into the given Kafka record headers. If MDC has no
   * correlation ID (e.g. a scheduled task initiated the message), a new UUID is generated.
   *
   * @param headers Kafka record headers to mutate.
   */
  public static void injectIntoHeaders(Headers headers) {
    String correlationId = CorrelationIdHolder.get();
    if (correlationId == null || correlationId.isBlank()) {
      // Scheduled / background producers: mint a fresh ID rather than propagating null.
      correlationId = UUID.randomUUID().toString();
      log.debug("No MDC correlationId; minted new ID for Kafka message: {}", correlationId);
    }
    headers.add(
        CorrelationIdHolder.KAFKA_HEADER,
        correlationId.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Extracts the correlation ID from Kafka record headers and returns it. If the header is absent
   * or blank, returns a freshly-minted UUID. Does NOT set MDC — the caller is responsible for
   * setting and clearing MDC.
   *
   * @param headers Kafka record headers from the consumed record.
   * @return The extracted or freshly-generated correlation ID.
   */
  public static String extractFromHeaders(Headers headers) {
    var header = headers.lastHeader(CorrelationIdHolder.KAFKA_HEADER);
    if (header == null || header.value() == null) {
      String newId = UUID.randomUUID().toString();
      log.debug(
          "Kafka record missing correlationId header; minting new ID: {}", newId);
      return newId;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
