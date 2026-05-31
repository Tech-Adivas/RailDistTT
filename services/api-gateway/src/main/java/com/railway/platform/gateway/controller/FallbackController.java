package com.railway.platform.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Circuit-breaker fallback endpoint.
 *
 * <p>Spring Cloud Gateway forwards here when a Resilience4j circuit is open (or the upstream
 * times out). Returns HTTP 503 with a {@code Retry-After: 30} header so clients back off
 * gracefully rather than hammering an unavailable service.
 *
 * <p>The {@code service} path variable maps to the Resilience4j circuit-breaker instance name
 * used in {@link com.railway.platform.gateway.config.GatewayRoutesConfig}:
 * {@code timetable-service}, {@code query-service}, or {@code distribution-service}.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

  private static final int RETRY_AFTER_SECONDS = 30;
  private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

  @GetMapping("/{service}")
  public Mono<ResponseEntity<Map<String, Object>>> fallbackGet(
      @PathVariable String service,
      ServerWebExchange exchange) {
    return fallback(service, exchange);
  }

  @PostMapping("/{service}")
  public Mono<ResponseEntity<Map<String, Object>>> fallbackPost(
      @PathVariable String service,
      ServerWebExchange exchange) {
    return fallback(service, exchange);
  }

  private Mono<ResponseEntity<Map<String, Object>>> fallback(
      String service, ServerWebExchange exchange) {

    String correlationId = exchange.getRequest().getHeaders()
        .getFirst("X-Correlation-Id");

    log.warn("Circuit open for service={} correlationId={}", service, correlationId);

    Map<String, Object> body = Map.of(
        "error", "service_unavailable",
        "service", service,
        "message", "The " + service + " is temporarily unavailable. Please retry later.",
        "retryAfter", RETRY_AFTER_SECONDS
    );

    return Mono.just(ResponseEntity
        .status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", String.valueOf(RETRY_AFTER_SECONDS))
        .header("X-Correlation-Id", correlationId != null ? correlationId : "")
        .body(body));
  }
}
