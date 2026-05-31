package com.railway.platform.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that populates the correlation ID MDC entry for every HTTP request.
 *
 * <p>If the incoming request carries an {@code X-Correlation-Id} header (propagated by the API
 * Gateway), that value is used. Otherwise, a new UUID v4 is minted. The correlation ID is:
 * <ol>
 *   <li>Placed into MDC so every log line for this request includes it.
 *   <li>Added to the HTTP response as {@code X-Correlation-Id} so clients can correlate errors.
 * </ol>
 *
 * <p>MDC is cleared in the finally block to prevent leakage into the next request on this thread
 * (critical for thread-pool-based servers).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String correlationId = request.getHeader(CorrelationIdHolder.HEADER_NAME);
    if (correlationId == null || correlationId.isBlank()) {
      // Mint a new ID if the gateway didn't supply one (direct service call or missing header).
      correlationId = UUID.randomUUID().toString();
    }

    try {
      CorrelationIdHolder.set(correlationId);
      // Echo the correlation ID in the response so the caller can reference it in support tickets.
      response.setHeader(CorrelationIdHolder.HEADER_NAME, correlationId);
      chain.doFilter(request, response);
    } finally {
      // Must clear MDC — thread pool servers reuse threads; leaked MDC bleeds into unrelated requests.
      CorrelationIdHolder.clear();
    }
  }
}
