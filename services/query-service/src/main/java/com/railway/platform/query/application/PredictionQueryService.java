package com.railway.platform.query.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railway.platform.query.api.dto.PredictionView;
import com.railway.platform.query.infrastructure.persistence.repository.DelayPredictionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Application service for delay prediction read queries.
 *
 * <p>Cache-aside pattern:
 * <ol>
 *   <li>Check Redis — return if hit.</li>
 *   <li>Query PostgreSQL read model — return empty list if absent (not 404).</li>
 *   <li>Populate Redis cache with 60 s TTL.</li>
 *   <li>Return result.</li>
 * </ol>
 *
 * <p>On Redis unavailability the service logs a WARN and falls through to the DB.
 * Cache eviction is triggered by {@link #invalidateCache(String)} which the projector
 * calls immediately after writing a new prediction row.
 */
@Service
@Transactional(readOnly = true)
public class PredictionQueryService {

  private static final Logger log = LoggerFactory.getLogger(PredictionQueryService.class);

  private static final String CACHE_PREFIX = "cache:prediction:";
  private static final long CACHE_TTL_SECONDS = 60;

  private final DelayPredictionRepository repository;
  private final RedisTemplate<String, Object> redisTemplate;
  private final ObjectMapper objectMapper;

  public PredictionQueryService(
      DelayPredictionRepository repository,
      RedisTemplate<String, Object> redisTemplate,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Returns delay predictions for the given route, optionally filtered by train.
   *
   * <p>An empty list is returned (never null) if no predictions exist.
   *
   * @param routeId the railway line identifier (e.g. LINE-VIC-BRI)
   * @param trainId optional service ID for train-level filtering; null for all predictions
   * @return list of {@link PredictionView} ordered by predictedAt descending
   */
  public List<PredictionView> getPredictions(String routeId, String trainId) {
    String cacheKey = cacheKey(routeId);

    // ── Cache read ──────────────────────────────────────────────────────────
    try {
      Object cached = redisTemplate.opsForValue().get(cacheKey);
      if (cached instanceof String json) {
        List<PredictionView> views = objectMapper.readValue(
            json, new TypeReference<List<PredictionView>>() {});
        log.debug("Cache hit [routeId={}]", routeId);
        return filterByTrainId(views, trainId);
      }
    } catch (Exception e) {
      log.warn("Redis unavailable or deserialization failed for [routeId={}] — querying DB: {}",
          routeId, e.getMessage());
    }

    // ── DB read ─────────────────────────────────────────────────────────────
    log.debug("Cache miss [routeId={}] — querying DB", routeId);
    var entities = repository.findByRouteIdOrderByPredictedAtDesc(routeId);
    List<PredictionView> views = entities.stream()
        .map(e -> new PredictionView(
            e.getRouteId(),
            e.getTrainId(),
            e.getPredictedDelayMinutes(),
            e.getConfidenceScore(),
            e.getModelVersion(),
            e.getPredictedAt()))
        .toList();

    // ── Write-through cache ─────────────────────────────────────────────────
    try {
      String json = objectMapper.writeValueAsString(views);
      redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(CACHE_TTL_SECONDS));
    } catch (Exception e) {
      log.warn("Failed to cache predictions for [routeId={}] — skipping: {}", routeId,
          e.getMessage());
    }

    return filterByTrainId(views, trainId);
  }

  /**
   * Deletes the Redis cache entry for the given route.
   *
   * <p>Called by {@code PredictionQueryProjector} after writing a new prediction row,
   * so the next read fetches fresh data from the DB.
   *
   * @param routeId the railway line identifier whose cache should be invalidated
   */
  public void invalidateCache(String routeId) {
    try {
      redisTemplate.delete(cacheKey(routeId));
      log.debug("Invalidated prediction cache [routeId={}]", routeId);
    } catch (Exception e) {
      log.warn("Failed to invalidate prediction cache [routeId={}]: {}", routeId, e.getMessage());
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private String cacheKey(String routeId) {
    return CACHE_PREFIX + routeId;
  }

  private List<PredictionView> filterByTrainId(List<PredictionView> views, String trainId) {
    if (trainId == null || trainId.isBlank()) {
      return views;
    }
    return views.stream()
        .filter(v -> trainId.equals(v.trainId()))
        .toList();
  }
}
