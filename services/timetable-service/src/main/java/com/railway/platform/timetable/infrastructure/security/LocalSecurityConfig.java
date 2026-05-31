package com.railway.platform.timetable.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permissive security config for local development and integration tests.
 *
 * <p>In the local and test profiles, all requests are permitted without authentication.
 * This allows running the service locally without an OIDC provider.
 * NEVER activate this in staging or production.
 */
@Configuration
@Profile({"local", "test"})
public class LocalSecurityConfig {

  @Bean
  public SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
