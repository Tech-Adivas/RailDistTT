package com.railway.platform.common.exception;

import com.railway.platform.common.error.ErrorCodes;

/**
 * Thrown when a concurrent write is detected via optimistic locking ({@code @Version} mismatch).
 *
 * <p>Maps to HTTP 409 Conflict. The client must re-fetch the resource, apply its change, and retry.
 * This is intentionally not a 5xx — it is an expected outcome under concurrent writes.
 */
public class OptimisticLockException extends DomainException {

  public OptimisticLockException(String resourceType, Object resourceId) {
    super(
        ErrorCodes.OPTIMISTIC_LOCK_CONFLICT,
        409,
        String.format(
            "%s with id '%s' was modified concurrently. Re-fetch and retry.", resourceType, resourceId));
  }
}
