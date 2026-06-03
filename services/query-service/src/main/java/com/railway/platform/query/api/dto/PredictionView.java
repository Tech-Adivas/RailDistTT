package com.railway.platform.query.api.dto;

import java.time.Instant;

/**
 * Immutable read DTO for a single delay prediction.
 *
 * <p>Returned by {@code PredictionQueryController} and cached in Redis by
 * {@code PredictionQueryService}.
 */
public record PredictionView(
    String routeId,
    String trainId,
    int predictedDelayMinutes,
    double confidenceScore,
    String modelVersion,
    Instant predictedAt
) {}
