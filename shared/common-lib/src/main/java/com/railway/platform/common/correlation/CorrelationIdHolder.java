package com.railway.platform.common.correlation;

import org.slf4j.MDC;

/**
 * Thread-local holder for the correlation ID.
 *
 * <p>The correlation ID is the single thread of traceability across the platform:
 *
 * <pre>
 * Browser → X-Correlation-Id header
 *   → API Gateway (mints if absent, propagates)
 *     → Service MDC (every log line includes it)
 *       → Kafka record header: correlationId
 *         → Consumer MDC (restored from header on consume)
 *           → Response header back to browser
 * </pre>
 *
 * <p>This class wraps MDC to ensure consistent key naming and provides a clear API for setting,
 * getting, and clearing the value. Never access MDC directly outside this class.
 */
public final class CorrelationIdHolder {

  /** MDC key name — must match the pattern in logback-spring.xml. */
  public static final String MDC_KEY = "correlationId";

  /** HTTP request/response header name. */
  public static final String HEADER_NAME = "X-Correlation-Id";

  /** Kafka record header name. */
  public static final String KAFKA_HEADER = "correlationId";

  private CorrelationIdHolder() {}

  /** Sets the correlation ID in MDC. Must call {@link #clear()} in a finally block. */
  public static void set(String correlationId) {
    MDC.put(MDC_KEY, correlationId);
  }

  /** Returns the current correlation ID, or {@code null} if not set. */
  public static String get() {
    return MDC.get(MDC_KEY);
  }

  /** Removes the correlation ID from MDC. Call in finally blocks to prevent leaks across threads. */
  public static void clear() {
    MDC.remove(MDC_KEY);
  }
}
