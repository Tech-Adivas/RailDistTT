# ADR-005: Spring Boot 4.0.6 with Spring Cloud 2025.0.1

## Status
Accepted

## Context
The build prompt specifies Spring Boot 4.0.6. Spring Boot 4.x requires Spring Framework 7.x and is only compatible with Spring Cloud 2025.x BOM. The previous Spring Cloud 2023.x and 2022.x BOMs are incompatible.

## Decision
Use:
- Spring Boot: `4.0.6`
- Spring Cloud BOM: `2025.0.1`
- All Spring Cloud components (Gateway, Vault, Circuit Breaker) sourced from this BOM.

Assumption (recorded here because the 2025.0.x BOM release schedule is tied to Boot 4.0.x GA): if `2025.0.1` is not yet published at build time, use `2025.0.0` and note the bump. The BOM's bill of materials ensures all sub-projects are mutually compatible.

## Consequences
**Positive:**
- Consistent, tested dependency graph via the BOM.
- Spring Boot 4 virtual-thread support (Project Loom) available for I/O-bound services.
- Latest security patches.

**Negative:**
- Some third-party libraries may not yet have Boot 4-compatible releases. Verify at build time and document any shims in `docs/VERSIONS.md`.

## Alternatives Considered
- **Spring Boot 3.x**: Mature but lacks Boot 4 features (virtual threads stable, new security defaults). The build prompt explicitly requires 4.0.6.
