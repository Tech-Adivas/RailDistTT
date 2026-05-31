package com.railway.platform.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Redis-backed rate limiter configuration for Spring Cloud Gateway.
 *
 * <p>Key resolution strategy:
 * <ol>
 *   <li>Authenticated: the JWT {@code sub} claim (stable per-user key)</li>
 *   <li>Unauthenticated: remote IP address (covers /actuator and /fallback)</li>
 * </ol>
 *
 * <p>Rate: {@code replenishRate} tokens/second refilled; {@code burstCapacity} max bucket size.
 * TODO(config): Tune replenishRate and burstCapacity in Vault secret/api-gateway/rate-limit.
 */
@Configuration
public class RateLimitConfig {

  // TODO(config): Vault path secret/api-gateway/rate-limit → replenish-rate
  @Value("${gateway.rate-limiter.replenish-rate:100}")
  private int replenishRate;

  // TODO(config): Vault path secret/api-gateway/rate-limit → burst-capacity
  @Value("${gateway.rate-limiter.burst-capacity:200}")
  private int burstCapacity;

  @Value("${gateway.rate-limiter.requested-tokens:1}")
  private int requestedTokens;

  @Bean
  public RedisRateLimiter redisRateLimiter() {
    return new RedisRateLimiter(replenishRate, burstCapacity, requestedTokens);
  }

  /**
   * Resolves the rate-limit bucket key.
   *
   * <p>JWT sub claim when authenticated; X-Forwarded-For / remote address as fallback.
   * Anonymous access to public endpoints (health, fallback) is rate-limited by IP.
   */
  @Bean
  public KeyResolver userKeyResolver() {
    return exchange -> {
      var principal = exchange.getPrincipal();
      return principal
          .map(p -> "user:" + p.getName())
          .switchIfEmpty(Mono.fromSupplier(() -> {
            var remoteAddress = exchange.getRequest().getRemoteAddress();
            var forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
              // Use only the leftmost (client) IP from the X-Forwarded-For chain
              return "ip:" + forwarded.split(",")[0].trim();
            }
            return "ip:" + (remoteAddress != null ? remoteAddress.getHostString() : "unknown");
          }));
    };
  }
}
