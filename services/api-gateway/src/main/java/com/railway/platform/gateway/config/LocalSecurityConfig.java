package com.railway.platform.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * DEV-ONLY security configuration that disables JWT validation for the "local" profile.
 *
 * <p>Active only when {@code spring.profiles.active=local}. In this mode all requests
 * are permitted without authentication so developers can call the gateway without an
 * OIDC provider. NEVER activate in staging or production.
 *
 * <p>Mutually exclusive with {@link SecurityConfig} which is active on {@code !local}.
 */
@Configuration
@EnableWebFluxSecurity
@Profile("local")
public class LocalSecurityConfig {

  @Bean
  public SecurityWebFilterChain localSecurityWebFilterChain(ServerHttpSecurity http) {
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
        .build();
  }
}
