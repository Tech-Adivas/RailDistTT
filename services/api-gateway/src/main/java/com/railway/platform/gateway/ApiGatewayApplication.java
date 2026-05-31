package com.railway.platform.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway — single ingress point for all Railway Platform services.
 *
 * <p>Responsibilities: OIDC JWT validation, RBAC enforcement, rate limiting (Redis),
 * circuit breakers (Resilience4j), correlation ID propagation, and routing to
 * timetable-service, query-service, and distribution-service.
 */
@SpringBootApplication
public class ApiGatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(ApiGatewayApplication.class, args);
  }
}
