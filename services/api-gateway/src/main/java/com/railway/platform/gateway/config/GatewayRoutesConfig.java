package com.railway.platform.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Spring Cloud Gateway route definitions.
 *
 * <p>Routing strategy (CQRS split):
 * <ul>
 *   <li>GET → query-service (port 8083) — read model, Redis-cached</li>
 *   <li>POST/PATCH → timetable-service (port 8081) — write model</li>
 *   <li>WebSocket upgrade → distribution-service (port 8084)</li>
 * </ul>
 *
 * <p>Each route attaches a Resilience4j circuit breaker and a Redis-backed rate limiter.
 * Circuit-open requests are forwarded to /fallback/{service} which returns HTTP 503.
 */
@Configuration
public class GatewayRoutesConfig {

  // TODO(config): Replace with Terraform outputs or Consul service addresses.
  // In Kubernetes: use ClusterIP service names (e.g. http://timetable-service:8081).
  @Value("${gateway.upstream.timetable-service-uri:http://PLACEHOLDER_TIMETABLE_SERVICE_HOST:8081}")
  private String timetableServiceUri;

  @Value("${gateway.upstream.query-service-uri:http://PLACEHOLDER_QUERY_SERVICE_HOST:8083}")
  private String queryServiceUri;

  @Value("${gateway.upstream.distribution-service-uri:http://PLACEHOLDER_DISTRIBUTION_SERVICE_HOST:8084}")
  private String distributionServiceUri;

  @Bean
  public RouteLocator gatewayRoutes(
      RouteLocatorBuilder builder,
      RedisRateLimiter rateLimiter,
      KeyResolver keyResolver) {

    return builder.routes()

        // ── Emergency activation ─────────────────────────────────────────
        // Most specific write route — must precede the generic timetable write route.
        .route("timetable-emergency-activate", r -> r
            .path("/api/v1/timetables/*/emergency-activate")
            .and().method(HttpMethod.POST)
            .filters(f -> f
                .circuitBreaker(c -> c
                    .setName("timetable-service")
                    .setFallbackUri("forward:/fallback/timetable-service"))
                .requestRateLimiter(rl -> rl
                    .setRateLimiter(rateLimiter)
                    .setKeyResolver(keyResolver)))
            .uri(timetableServiceUri))

        // ── Timetable approval workflow (POST) ───────────────────────────
        .route("timetable-approval-workflow", r -> r
            .path(
                "/api/v1/timetables/*/approve",
                "/api/v1/timetables/*/reject",
                "/api/v1/timetables/*/request-changes",
                "/api/v1/timetables/*/submit",
                "/api/v1/timetables/*/cancel")
            .and().method(HttpMethod.POST)
            .filters(f -> f
                .circuitBreaker(c -> c
                    .setName("timetable-service")
                    .setFallbackUri("forward:/fallback/timetable-service"))
                .requestRateLimiter(rl -> rl
                    .setRateLimiter(rateLimiter)
                    .setKeyResolver(keyResolver)))
            .uri(timetableServiceUri))

        // ── Timetable create (POST) and update (PATCH) ───────────────────
        .route("timetable-write", r -> r
            .path("/api/v1/timetables", "/api/v1/timetables/**")
            .and().method(HttpMethod.POST, HttpMethod.PATCH)
            .filters(f -> f
                .circuitBreaker(c -> c
                    .setName("timetable-service")
                    .setFallbackUri("forward:/fallback/timetable-service"))
                .requestRateLimiter(rl -> rl
                    .setRateLimiter(rateLimiter)
                    .setKeyResolver(keyResolver)))
            .uri(timetableServiceUri))

        // ── Read routes → query-service ──────────────────────────────────
        // Audit log read → timetable-service (write-side DB).
        // Must precede the generic timetable-read route so audit requests do NOT get
        // forwarded to query-service, which has no access to the write-side audit_log table.
        .route("timetable-audit", r -> r
            .path("/api/v1/timetables/*/audit")
            .and().method(HttpMethod.GET)
            .filters(f -> f
                .circuitBreaker(c -> c
                    .setName("timetable-service")
                    .setFallbackUri("forward:/fallback/timetable-service"))
                .requestRateLimiter(rl -> rl
                    .setRateLimiter(rateLimiter)
                    .setKeyResolver(keyResolver)))
            .uri(timetableServiceUri))

        .route("timetable-read", r -> r
            .path("/api/v1/timetables/**")
            .and().method(HttpMethod.GET)
            .filters(f -> f
                .circuitBreaker(c -> c
                    .setName("query-service")
                    .setFallbackUri("forward:/fallback/query-service"))
                .requestRateLimiter(rl -> rl
                    .setRateLimiter(rateLimiter)
                    .setKeyResolver(keyResolver)))
            .uri(queryServiceUri))

        .route("lines-read", r -> r
            .path("/api/v1/lines/**")
            .and().method(HttpMethod.GET)
            .filters(f -> f
                .circuitBreaker(c -> c
                    .setName("query-service")
                    .setFallbackUri("forward:/fallback/query-service"))
                .requestRateLimiter(rl -> rl
                    .setRateLimiter(rateLimiter)
                    .setKeyResolver(keyResolver)))
            .uri(queryServiceUri))

        .route("schedules-read", r -> r
            .path("/api/v1/schedules/**")
            .and().method(HttpMethod.GET)
            .filters(f -> f
                .circuitBreaker(c -> c
                    .setName("query-service")
                    .setFallbackUri("forward:/fallback/query-service"))
                .requestRateLimiter(rl -> rl
                    .setRateLimiter(rateLimiter)
                    .setKeyResolver(keyResolver)))
            .uri(queryServiceUri))

        // ── WebSocket upgrade → distribution-service ─────────────────────
        // No rate limiter on WS — long-lived connection; circuit breaker still applies.
        .route("websocket-distribution", r -> r
            .path("/ws/**")
            .filters(f -> f
                .circuitBreaker(c -> c
                    .setName("distribution-service")
                    .setFallbackUri("forward:/fallback/distribution-service")))
            .uri(distributionServiceUri.replace("http://", "ws://")))

        .build();
  }
}
