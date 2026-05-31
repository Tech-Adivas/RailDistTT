package com.railway.platform.common.exception;

import com.railway.platform.common.error.ErrorCodes;

/**
 * Base class for all domain-layer exceptions in the platform.
 *
 * <p>Subclasses carry a machine-readable {@code errorCode} (from {@link ErrorCodes}) and an HTTP
 * status code. The global exception handler ({@link GlobalExceptionHandler}) maps these to {@link
 * com.railway.platform.common.error.ApiError} responses without leaking stack traces.
 *
 * <p>Domain exceptions are intentional, expected outcomes (not-found, invalid state transition,
 * etc.). They are logged at WARN level. Unexpected exceptions ({@link RuntimeException} subclasses
 * not extending DomainException) are logged at ERROR level with a full stack trace.
 */
public abstract class DomainException extends RuntimeException {

  private final String errorCode;
  private final int httpStatus;

  protected DomainException(String errorCode, int httpStatus, String message) {
    super(message);
    this.errorCode = errorCode;
    this.httpStatus = httpStatus;
  }

  protected DomainException(String errorCode, int httpStatus, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
    this.httpStatus = httpStatus;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public int getHttpStatus() {
    return httpStatus;
  }
}
