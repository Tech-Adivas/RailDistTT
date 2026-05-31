package com.railway.platform.common.exception;

import com.railway.platform.common.error.ErrorCodes;

/**
 * Thrown when an operation is rejected because the aggregate is in an incompatible state.
 *
 * <p>For example, attempting to approve a timetable that is already in APPROVED status.
 * Maps to HTTP 422 Unprocessable Entity — the request was syntactically valid but semantically
 * incorrect given the current state of the resource.
 */
public class InvalidStateTransitionException extends DomainException {

  public InvalidStateTransitionException(String message) {
    super(ErrorCodes.TIMETABLE_INVALID_STATE_TRANSITION, 422, message);
  }

  public InvalidStateTransitionException(String errorCode, String message) {
    super(errorCode, 422, message);
  }
}
