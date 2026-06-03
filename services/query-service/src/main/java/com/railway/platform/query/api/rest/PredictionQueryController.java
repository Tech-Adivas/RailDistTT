package com.railway.platform.query.api.rest;

import com.railway.platform.query.api.dto.PredictionsResponse;
import com.railway.platform.query.application.PredictionQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Read-side REST API for delay predictions.
 *
 * <p>All endpoints are read-only (GET). Predictions are served from the
 * {@code delay_predictions} read model table, with Redis caching for 60 s.
 *
 * <p>An empty prediction list returns HTTP 200 with an empty array — NOT 404 —
 * because the absence of predictions is a valid state (no AI prediction has been
 * computed yet, or predictions have expired).
 */
@RestController
@RequestMapping("/api/v1")
public class PredictionQueryController {

  private final PredictionQueryService predictionQueryService;

  public PredictionQueryController(PredictionQueryService predictionQueryService) {
    this.predictionQueryService = predictionQueryService;
  }

  /**
   * GET /api/v1/predictions?routeId={routeId}[&trainId={trainId}][&effectiveDate={date}]
   *
   * <p>Returns delay predictions for the given route, optionally filtered by train.
   * The {@code effectiveDate} parameter is accepted for API compatibility but not
   * currently used for filtering (all predictions for the route are returned).
   *
   * @param routeId       required; railway line identifier (e.g. LINE-VIC-BRI)
   * @param trainId       optional; filter to a specific train/service ID
   * @param effectiveDate optional; reserved for future date-based filtering
   * @return HTTP 200 with a (possibly empty) {@link PredictionsResponse}
   */
  @GetMapping("/predictions")
  public ResponseEntity<PredictionsResponse> getPredictions(
      @RequestParam String routeId,
      @RequestParam(required = false) String trainId,
      @RequestParam(required = false) String effectiveDate) {

    var results = predictionQueryService.getPredictions(routeId, trainId);
    return ResponseEntity.ok(new PredictionsResponse(results));
  }
}
