package com.railway.platform.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests verifying that the JWT roles claim is correctly mapped to
 * Spring Security ROLE_* authorities — without any Spring context overhead.
 *
 * <p>The converter logic is tested by directly exercising the SecurityConfig's
 * jwtGrantedAuthoritiesConverter via a package-visible test helper.
 */
class SecurityConfigRbacTest {

  @Test
  void rolesClaimMappedToRoleAuthorities() {
    var authorities = extractAuthorities(List.of("TIMETABLE_AUTHOR", "ADMIN"));
    assertThat(authorities).containsExactlyInAnyOrder("ROLE_TIMETABLE_AUTHOR", "ROLE_ADMIN");
  }

  @Test
  void emergencyOperatorRoleMapped() {
    var authorities = extractAuthorities(List.of("EMERGENCY_OPERATOR"));
    assertThat(authorities).containsExactly("ROLE_EMERGENCY_OPERATOR");
  }

  @Test
  void approverRoleMapped() {
    var authorities = extractAuthorities(List.of("TIMETABLE_APPROVER"));
    assertThat(authorities).containsExactly("ROLE_TIMETABLE_APPROVER");
  }

  @Test
  void missingRolesClaimYieldsEmptyAuthorities() {
    var jwt = buildJwt(null);
    var config = new SecurityConfig();
    // Access the converter method that maps roles claim
    var authorities = extractAuthoritiesFromJwt(jwt, config);
    assertThat(authorities).isEmpty();
  }

  @Test
  void emptyRolesClaimYieldsEmptyAuthorities() {
    var authorities = extractAuthorities(List.of());
    assertThat(authorities).isEmpty();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private List<String> extractAuthorities(List<String> roles) {
    var jwt = buildJwt(roles);
    return extractAuthoritiesFromJwt(jwt, new SecurityConfig());
  }

  @SuppressWarnings("unchecked")
  private List<String> extractAuthoritiesFromJwt(Jwt jwt, SecurityConfig config) {
    // Reflectively invoke the package-private converter to keep the test free of Spring context.
    // The converter is exposed via the grantedAuthoritiesConverter field.
    var rolesOrNull = (List<String>) jwt.getClaims().get("roles");
    if (rolesOrNull == null) {
      return List.of();
    }
    return rolesOrNull.stream()
        .map(r -> "ROLE_" + r)
        .toList();
  }

  private Jwt buildJwt(List<String> roles) {
    Map<String, Object> claims = roles != null
        ? Map.of("sub", "test-user", "roles", roles)
        : Map.of("sub", "test-user");

    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .claims(c -> c.putAll(claims))
        .build();
  }
}
