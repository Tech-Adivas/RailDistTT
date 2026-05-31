package com.railway.platform.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reactive security configuration for the API Gateway.
 *
 * <p>JWT roles are extracted from the {@code roles} claim and mapped to Spring Security
 * {@code ROLE_} prefixed authorities. Downstream services receive the validated JWT
 * via the {@code Authorization} header — they do NOT re-validate it; they trust the gateway.
 *
 * <p>Route-level authorisation rules:
 * <ul>
 *   <li>Emergency activation — {@code ROLE_EMERGENCY_OPERATOR} only</li>
 *   <li>Approve/reject/request-changes — {@code ROLE_TIMETABLE_APPROVER}</li>
 *   <li>Create/update/submit/cancel timetables — {@code ROLE_TIMETABLE_AUTHOR}</li>
 *   <li>Audit log — any authenticated user</li>
 *   <li>All GETs — any authenticated user</li>
 *   <li>Actuator /health, /info — public (Kubernetes liveness/readiness probes)</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
@org.springframework.context.annotation.Profile("!local")
public class SecurityConfig {

  // TODO(config): Vault path secret/api-gateway/oidc → issuer-uri
  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:PLACEHOLDER_OIDC_ISSUER_URI}")
  private String issuerUri;

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(exchanges -> exchanges
            // Public — liveness/readiness probes, metrics scrape (network-restricted in prod)
            .pathMatchers("/actuator/health", "/actuator/info").permitAll()

            // Emergency activation — most privileged role only
            .pathMatchers(HttpMethod.POST, "/api/v1/timetables/*/emergency-activate")
                .hasRole("EMERGENCY_OPERATOR")

            // Approval workflow
            .pathMatchers(HttpMethod.POST,
                "/api/v1/timetables/*/approve",
                "/api/v1/timetables/*/reject",
                "/api/v1/timetables/*/request-changes")
                .hasAnyRole("TIMETABLE_APPROVER", "ADMIN")

            // Authoring workflow
            .pathMatchers(HttpMethod.POST,
                "/api/v1/timetables",
                "/api/v1/timetables/*/submit",
                "/api/v1/timetables/*/cancel")
                .hasAnyRole("TIMETABLE_AUTHOR", "ADMIN")

            .pathMatchers(HttpMethod.PATCH, "/api/v1/timetables/*")
                .hasAnyRole("TIMETABLE_AUTHOR", "ADMIN")

            // All other API calls — authenticated only
            .pathMatchers("/api/**").authenticated()

            // WebSocket upgrade — authenticated
            .pathMatchers("/ws/**").authenticated()

            // Fallback endpoints — open (no auth data available in fallback)
            .pathMatchers("/fallback/**").permitAll()

            .anyExchange().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt
                .jwtDecoder(jwtDecoder())
                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .build();
  }

  @Bean
  public ReactiveJwtDecoder jwtDecoder() {
    // TODO(config): In production use NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build()
    // For local dev, this uses the configured issuer-uri which must be reachable.
    return NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
  }

  /**
   * Converts the JWT {@code roles} claim into Spring Security {@code ROLE_*} authorities.
   * The claim name "roles" must match what the OIDC provider inserts into tokens.
   * TODO(config): Verify the roles claim name against your IdP (Cognito uses "cognito:groups").
   */
  private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      List<String> roles = jwt.getClaimAsStringList("roles");
      if (roles == null) {
        return List.of();
      }
      return roles.stream()
          .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
          .collect(Collectors.toList());
    });
    return new ReactiveJwtAuthenticationConverterAdapter(converter);
  }
}
