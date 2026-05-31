package com.railway.platform.common.error;

/**
 * Platform-wide error code constants.
 *
 * <p>Clients use these codes for programmatic error handling. Values are stable across releases;
 * do not rename or remove. Add new codes as needed; document each with a comment.
 */
public final class ErrorCodes {

  private ErrorCodes() {}

  // ── Generic ───────────────────────────────────────────────────────────────

  /** Request body or query parameters failed Bean Validation. */
  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

  /** The requested resource does not exist or the caller lacks visibility. */
  public static final String NOT_FOUND = "NOT_FOUND";

  /** The caller is not authenticated. */
  public static final String UNAUTHORIZED = "UNAUTHORIZED";

  /** The caller is authenticated but lacks the required role. */
  public static final String FORBIDDEN = "FORBIDDEN";

  /** An optimistic locking conflict was detected. Caller should re-fetch and retry. */
  public static final String OPTIMISTIC_LOCK_CONFLICT = "OPTIMISTIC_LOCK_CONFLICT";

  /** A downstream service is temporarily unavailable (circuit open or timeout). */
  public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

  /** Rate limit exceeded. Retry after the interval specified in Retry-After header. */
  public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

  /** An unexpected internal server error occurred. Correlation ID in response for support. */
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  // ── Timetable domain ─────────────────────────────────────────────────────

  /** The requested timetable does not exist. */
  public static final String TIMETABLE_NOT_FOUND = "TIMETABLE_NOT_FOUND";

  /** The timetable is in a state that does not permit the requested operation. */
  public static final String TIMETABLE_INVALID_STATE_TRANSITION = "TIMETABLE_INVALID_STATE_TRANSITION";

  /** The approval workflow requires a reviewer different from the original author. */
  public static final String TIMETABLE_SELF_APPROVAL_NOT_ALLOWED = "TIMETABLE_SELF_APPROVAL_NOT_ALLOWED";

  /** An emergency override requires a justification to be recorded. */
  public static final String EMERGENCY_JUSTIFICATION_REQUIRED = "EMERGENCY_JUSTIFICATION_REQUIRED";

  // ── Schedule domain ───────────────────────────────────────────────────────

  /** The referenced timetable has no valid schedules for the requested date range. */
  public static final String SCHEDULE_NOT_FOUND = "SCHEDULE_NOT_FOUND";

  // ── Maintenance domain ────────────────────────────────────────────────────

  /** The maintenance window overlaps with an existing window for the same track segment. */
  public static final String MAINTENANCE_WINDOW_OVERLAP = "MAINTENANCE_WINDOW_OVERLAP";

  /** The maintenance window references a track segment that does not exist. */
  public static final String TRACK_SEGMENT_NOT_FOUND = "TRACK_SEGMENT_NOT_FOUND";
}
