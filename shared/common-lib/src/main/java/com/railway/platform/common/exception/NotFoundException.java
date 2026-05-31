package com.railway.platform.common.exception;

import com.railway.platform.common.error.ErrorCodes;

/** Thrown when a requested resource does not exist or the caller lacks visibility to it. */
public class NotFoundException extends DomainException {

  public NotFoundException(String message) {
    super(ErrorCodes.NOT_FOUND, 404, message);
  }

  public NotFoundException(String errorCode, String message) {
    super(errorCode, 404, message);
  }
}
