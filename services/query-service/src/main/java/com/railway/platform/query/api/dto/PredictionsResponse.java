package com.railway.platform.query.api.dto;

import java.util.List;

/**
 * Response envelope for {@code GET /api/v1/predictions}.
 *
 * <p>Returns an empty list (never null) when no predictions exist for the requested route.
 */
public record PredictionsResponse(List<PredictionView> predictions) {}
