package com.railway.platform.query.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railway.platform.query.api.dto.TimetableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache-aside service for timetable read model views.
 *
 * <p>Cache key strategy:
 * <ul>
 *   <li>{@code timetable:{timetableId}} — single timetable view (TTL varies by status)</li>
 *   <li>{@code line-timetables:{lineId}} — invalidation marker; actual list re-queries DB</li>
 * </ul>
 *
 * <p>TTL policy:
 * <ul>
 *   <li>ACTIVE / EMERGENCY_ACTIVE — 5 minutes (relatively stable)</li>
 *   <li>DRAFT / PENDING_REVIEW — 30 seconds (changes frequently)</li>
 *   <li>Terminal states (APPROVED, REJECTED, SUPERSEDED, CANCELLED) — 10 minutes</li>
 * </ul>
 *
 * <p>The cache is evicted eagerly when the projector writes a new event for the same timetable,
 * so staleness is bounded by the window between the event and the projector's write.
 */
@Service
public class TimetableCacheService {

  private static final Logger log = LoggerFactory.getLogger(TimetableCacheService.class);

  private static final String KEY_PREFIX = "timetable:";
  private static final Duration TTL_ACTIVE = Duration.ofMinutes(5);
  private static final Duration TTL_MUTABLE = Duration.ofSeconds(30);
  private static final Duration TTL_TERMINAL = Duration.ofMinutes(10);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public TimetableCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  public Optional<TimetableView> get(String timetableId) {
    String key = key(timetableId);
    String json = redisTemplate.opsForValue().get(key);
    if (json == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(json, TimetableView.class));
    } catch (JsonProcessingException e) {
      log.warn("Failed to deserialize cached timetable [{}] — evicting", timetableId);
      redisTemplate.delete(key);
      return Optional.empty();
    }
  }

  public void put(TimetableView view) {
    try {
      String json = objectMapper.writeValueAsString(view);
      redisTemplate.opsForValue().set(key(view.id()), json, ttlFor(view.status()));
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize timetable [{}] for cache — skipping", view.id());
    }
  }

  public void evict(String timetableId) {
    redisTemplate.delete(key(timetableId));
    log.debug("Evicted cache entry [timetableId={}]", timetableId);
  }

  private Duration ttlFor(String status) {
    return switch (status) {
      case "ACTIVE", "EMERGENCY_ACTIVE" -> TTL_ACTIVE;
      case "APPROVED", "REJECTED", "SUPERSEDED", "CANCELLED" -> TTL_TERMINAL;
      default -> TTL_MUTABLE;
    };
  }

  private String key(String timetableId) {
    return KEY_PREFIX + timetableId;
  }
}
