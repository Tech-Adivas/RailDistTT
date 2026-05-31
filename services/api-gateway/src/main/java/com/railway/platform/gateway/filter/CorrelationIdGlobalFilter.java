package com.railway.platform.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global filter that mints or propagates an {@code X-Correlation-Id} header on every request.
 *
 * <p>Flow:
 * <ol>
 *   <li>If the inbound request already carries {@code X-Correlation-Id}, use it as-is.</li>
 *   <li>Otherwise generate a new UUID and attach it.</li>
 *   <li>Propagate the ID to all downstream services via the mutated request headers.</li>
 *   <li>Echo the ID back in the response so callers can correlate distributed traces.</li>
 * </ol>
 *
 * <p>Note: Reactor context (not MDC) is the correct vehicle for per-request context in WebFlux.
 * This filter propagates the ID via headers; integration with a reactive MDC adapter
 * (e.g. reactor-extra ContextPropagation) should be wired separately if structured log
 * correlation within this gateway process is required.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

  /**
   * Run before all built-in gateway filters so the ID is available to every downstream filter.
   * {@link Ordered#HIGHEST_PRECEDENCE} + 1 avoids clashing with any framework filter at exact
   * HIGHEST_PRECEDENCE while still running before route resolution.
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
    boolean minted = correlationId == null || correlationId.isBlank();
    if (minted) {
      correlationId = UUID.randomUUID().toString();
    }

    final String finalCorrelationId = correlationId;
    log.debug("correlationId={} minted={} path={}", finalCorrelationId, minted,
        exchange.getRequest().getPath());

    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
        .header(CORRELATION_ID_HEADER, finalCorrelationId)
        .build();

    ServerWebExchange mutatedExchange = exchange.mutate()
        .request(mutatedRequest)
        .build();

    return chain.filter(mutatedExchange)
        .doOnSuccess(v -> mutatedExchange.getResponse().getHeaders()
            .set(CORRELATION_ID_HEADER, finalCorrelationId));
  }
}
