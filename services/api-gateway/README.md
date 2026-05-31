# services/api-gateway

## Purpose

Single entry point for all client traffic. Handles OIDC JWT validation, RBAC enforcement, per-user rate limiting (Redis), Resilience4j circuit breaker + bulkhead, correlation-ID minting, and request routing to downstream services.

## Architecture

```mermaid
sequenceDiagram
  participant C as Client
  participant GW as API Gateway
  participant OIDC as OIDC Provider
  participant SVC as Downstream Service

  C->>GW: Request + Bearer JWT
  GW->>OIDC: Validate JWT (JWKS)
  OIDC-->>GW: Valid / Invalid
  GW->>GW: RBAC check
  GW->>GW: Rate limit check (Redis)
  GW->>GW: Mint X-Correlation-Id
  GW->>SVC: Forward request
  SVC-->>GW: Response
  GW-->>C: Response + X-Correlation-Id header
```

## Error Handling

| Scenario | Response |
|----------|---------|
| Invalid JWT | 401 |
| Insufficient role | 403 |
| Rate limit exceeded | 429 + `Retry-After` header |
| Circuit breaker open | 503 + `Retry-After`; fallback controller |
| Bulkhead full | 503 fast-fail |
| Downstream timeout | 504 |
