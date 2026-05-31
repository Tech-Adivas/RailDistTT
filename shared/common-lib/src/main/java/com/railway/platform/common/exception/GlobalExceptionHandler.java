package com.railway.platform.common.exception;

import com.railway.platform.common.correlation.CorrelationIdHolder;
import com.railway.platform.common.error.ApiError;
import com.railway.platform.common.error.ErrorCodes;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates all exceptions into {@link ApiError} HTTP responses.
 *
 * <p>The mapping is explicit and exhaustive — no exception reaches the default Spring error page.
 * Every catch path either:
 * <ul>
 *   <li>Returns a structured {@link ApiError} with the appropriate HTTP status, OR
 *   <li>Logs the unexpected error at ERROR level (with stack trace) and returns a 500.
 * </ul>
 * Stack traces are NEVER included in the response body to avoid leaking internal details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * Handles domain exceptions — expected, intentional failures such as not-found, optimistic-lock
   * conflicts, and state-machine violations. Logged at WARN (not ERROR) since they are not bugs.
   */
  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiError> handleDomainException(DomainException ex) {
    String correlationId = CorrelationIdHolder.get();
    log.warn(
        "Domain exception [correlationId={}] [code={}]: {}",
        correlationId,
        ex.getErrorCode(),
        ex.getMessage());

    ApiError error = ApiError.of(ex.getErrorCode(), ex.getMessage(), correlationId);
    return ResponseEntity.status(ex.getHttpStatus()).body(error);
  }

  /**
   * Handles Spring MVC validation failures ({@code @Valid} on request body). Returns 422 with
   * per-field error details so clients can highlight the offending form fields.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    String correlationId = CorrelationIdHolder.get();

    List<ApiError.FieldError> fieldErrors =
        ex.getBindingResult().getAllErrors().stream()
            .filter(e -> e instanceof FieldError)
            .map(e -> (FieldError) e)
            .map(
                fe ->
                    new ApiError.FieldError(
                        fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
            .toList();

    log.warn(
        "Validation failed [correlationId={}] [fields={}]",
        correlationId,
        fieldErrors.stream().map(ApiError.FieldError::field).toList());

    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(ApiError.ofValidation(correlationId, fieldErrors));
  }

  /**
   * Handles Bean Validation constraint violations on method parameters (e.g. path variables,
   * query params annotated with {@code @Validated}).
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
    String correlationId = CorrelationIdHolder.get();

    List<ApiError.FieldError> fieldErrors =
        ex.getConstraintViolations().stream()
            .map(
                cv ->
                    new ApiError.FieldError(
                        cv.getPropertyPath().toString(),
                        cv.getInvalidValue(),
                        cv.getMessage()))
            .toList();

    log.warn(
        "Constraint violation [correlationId={}] [fields={}]",
        correlationId,
        fieldErrors.stream().map(ApiError.FieldError::field).toList());

    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(ApiError.ofValidation(correlationId, fieldErrors));
  }

  /**
   * Catch-all for any unexpected exceptions. Logged at ERROR with full stack trace. The response
   * body contains a generic message and the correlationId so the caller can reference the log.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
    String correlationId = CorrelationIdHolder.get();
    log.error(
        "Unexpected error [correlationId={}]: {}",
        correlationId,
        ex.getMessage(),
        ex); // stack trace in log, NOT in response

    ApiError error =
        ApiError.of(
            ErrorCodes.INTERNAL_ERROR,
            "An unexpected error occurred. Please reference correlationId in your support request.",
            correlationId);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
  }
}
