package com.railway.platform.distribution.orchestrator;

/**
 * Outcome of a single channel distribution attempt.
 *
 * @param channel       DistributionChannel enum name — kept as String to avoid coupling callers
 *                      to the Avro-generated enum in value-object contexts.
 * @param success       True when the channel accepted the payload without error.
 * @param failureReason Human-readable explanation when success is false; null on success.
 */
public record DistributionResult(
    String channel,
    boolean success,
    String failureReason
) {

  public static DistributionResult ok(String channel) {
    return new DistributionResult(channel, true, null);
  }

  public static DistributionResult failed(String channel, String reason) {
    return new DistributionResult(channel, false, reason);
  }
}
