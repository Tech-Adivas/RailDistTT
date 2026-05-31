package com.railway.platform.events;

/**
 * Kafka topic name constants shared across all services.
 *
 * <p>Always import from this class rather than hardcoding topic names in producers or consumers.
 * Topic names must match the Debezium outbox router configuration in infra/debezium/application.properties.
 */
public final class Topics {

  private Topics() {}

  /** Timetable state changes from the Transactional Outbox (Debezium). */
  public static final String TIMETABLE_CHANGED = "railway.timetable.changed";

  /** Effective schedule computed by Schedule Service. */
  public static final String SCHEDULE_COMPUTED = "railway.schedule.computed";

  /** Distribution channel delivery tracking (saga events). */
  public static final String DISTRIBUTION_EVENTS = "railway.distribution.events";

  /** Notification requests for push/SMS/email. */
  public static final String NOTIFICATION_REQUESTS = "railway.notification.requests";

  /** Track maintenance window lifecycle events. */
  public static final String MAINTENANCE_WINDOWS = "railway.maintenance.windows";

  /**
   * Dead Letter Queue — receives messages that failed all retries or are unprocessable (poison).
   * Every service routes its unprocessable messages here with the original topic as a header.
   */
  public static final String DLQ = "railway.dlq";
}
