package com.railway.platform.timetable.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Security configuration for the Timetable Service.
 *
 * <p>Authentication: OIDC JWT validated via Spring Security OAuth2 Resource Server.
 * The JWT is issued by the gateway's OIDC provider and validated against the JWKS endpoint.
 *
 * <p>Authorization: Method-level RBAC via {@code @PreAuthorize} in the controller.
 * Roles are extracted from the JWT {@code roles} claim (standard claim for Cognito / Keycloak).
 *
 * <p>The service is stateless — no session is ever created.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

  /**
   * Production security filter chain — validates JWT for every request except Actuator health.
   * Not active for the local profile (see {@link LocalSecurityConfig}).
   */
  @Bean
  @Profile("!local & !test")
  public SecurityFilterChain productionFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())     // API is stateless; CSRF protection is not applicable.
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            // Actuator health/readiness endpoints are open for K8s probes.
            .requestMatchers("/actuator/health/**", "/actuator/liveness", "/actuator/readiness").permitAll()
            // Prometheus scrape endpoint is open for Prometheus scraper.
            .requestMatchers("/actuator/prometheus").permitAll()
            // OpenAPI docs are open (internal service, gateway blocks external access).
            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
            // All other requests require a valid JWT.
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .build();
  }

  /**
   * Extracts RBAC roles from the JWT {@code roles} claim and maps them to Spring
   * GrantedAuthority objects (with "ROLE_" prefix for {@code @PreAuthorize} compatibility).
   */
  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      @SuppressWarnings("unchecked")
      List<String> roles = (List<String>) jwt.getClaims().getOrDefault("roles", List.of());
      return roles.stream()
          .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
          .collect(Collectors.toList());
    });
    return converter;
  }
}
