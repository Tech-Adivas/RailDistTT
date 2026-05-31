package com.railway.platform.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Standard error response envelope returned by every service in the platform.
 *
 * <p>All error paths — validation, domain constraint violations, unexpected errors — produce this
 * shape. Clients can rely on {@code code} for programmatic handling without parsing {@code message}.
 *
 * <p>Field-level validation errors populate {@code fieldErrors}; they are omitted from the JSON
 * when null to keep simple error responses lean.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    /** Machine-readable error code (e.g. TIMETABLE_NOT_FOUND, VALIDATION_ERROR). */
    String code,

    /** Human-readable message suitable for logging; NOT intended for display in UI. */
    String message,

    /**
     * Correlation ID of the originating request. Allows a client to link a reported error to the
     * server-side log line. Minted at the API Gateway; propagated via X-Correlation-Id header.
     */
    String correlationId,

    /** ISO-8601 timestamp when the error was generated on the server. */
    Instant timestamp,

    /**
     * Field-level validation errors. Only present for 422 Unprocessable Entity responses. Each
     * entry identifies the offending field and the reason.
     */
    List<FieldError> fieldErrors) {

  /** Convenience factory for non-validation errors (no field errors). */
  public static ApiError of(String code, String message, String correlationId) {
    return new ApiError(code, message, correlationId, Instant.now(), null);
  }

  /** Convenience factory for validation errors (with field errors). */
  public static ApiError ofValidation(
      String correlationId, List<FieldError> fieldErrors) {
    return new ApiError(
        ErrorCodes.VALIDATION_ERROR,
        "Request validation failed. See fieldErrors for details.",
        correlationId,
        Instant.now(),
        fieldErrors);
  }

  /**
   * A single field-level validation error.
   *
   * @param field JSON path of the invalid field (e.g. "timetable.effectiveDate").
   * @param rejectedValue The value that was submitted (may be null).
   * @param reason Why the value was rejected (e.g. "must not be null", "must be a future date").
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FieldError(String field, Object rejectedValue, String reason) {}
}
