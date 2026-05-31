# Resolved Dependency Versions

> Last verified: 2026-05-31. Re-verify before first production deployment.
> Policy: pin to exact patch; upgrade when a CVE is published in the same minor line.

## Core Runtime

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| Java | 21.0.7 (Eclipse Temurin) | LTS; use the Temurin 21 Docker image |
| Spring Boot | 4.0.6 | Requires Spring Framework 7.x |
| Spring Framework | 7.0.x (managed by Boot) | Transitioned to virtual threads support |
| Spring Cloud | 2025.0.1 | First GA release compatible with Boot 4.0.x |
| Spring Cloud Gateway | 5.0.x (via SC BOM) | Reactive; replaces older MVC gateway |
| Spring Cloud Vault | 5.0.x (via SC BOM) | |
| Spring Data JPA | 4.0.x (via Boot BOM) | |
| Spring Data Redis | 4.0.x (via Boot BOM) | |
| Spring Kafka | 4.0.x (via Boot BOM) | |

## Messaging

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| Apache Kafka | 4.3.0 | KRaft mode; ZooKeeper removed |
| Confluent Schema Registry | 7.9.1 | Must be on same Confluent Platform minor as Kafka version |
| Apache Avro | 1.12.0 | |
| kafka-avro-serializer | 7.9.1 | io.confluent:kafka-avro-serializer |

## Database & Cache

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| PostgreSQL | 16.9 | Use postgres:16 Docker image |
| Flyway | 11.9.0 | Boot 4 compatible; use flyway-database-postgresql |
| HikariCP | 6.x (via Boot BOM) | |
| Redis | 7.4.2 | Use redis:7.4-alpine Docker image |
| Lettuce (Redis client) | 7.x (via Boot BOM) | |

## CDC / Outbox

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| Debezium Server | 3.1.2.Final | Standalone relay for the outbox pattern |
| Debezium PostgreSQL connector | 3.1.2.Final | Logical replication via pgoutput |

## Security & Secrets

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| HashiCorp Vault | 1.19.3 | |
| Spring Cloud Vault | 5.0.x | |
| Spring Security | 7.x (via Boot BOM) | |
| spring-security-oauth2-resource-server | 7.x | OIDC JWT validation |

## Observability

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| Micrometer | 1.15.x (via Boot BOM) | |
| micrometer-registry-prometheus | 1.15.x | |
| OpenTelemetry Java agent | 2.16.0 | Attach as javaagent; auto-instruments Spring |
| OpenTelemetry SDK | 1.49.0 | |
| Prometheus | 3.4.1 | |
| Grafana | 12.0.x | |
| Loki | 3.5.x | |
| Grafana Tempo | 2.8.x | |
| Grafana Alloy (OTel collector) | 1.8.x | |

## Frontend

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| Node.js | 22.x LTS | Use node:22-alpine in Docker |
| Angular CLI | 21.0.x | |
| Angular | 21.0.x | Standalone components, signals, zoneless |
| NgRx Signals Store | 19.x | Compatible with Angular 21 |
| TypeScript | 5.8.x | Required by Angular 21 |

## Testing

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| JUnit 5 | 5.12.x (via Boot BOM) | |
| Mockito | 5.x (via Boot BOM) | |
| Testcontainers | 1.21.x | Core + PostgreSQL + Kafka + Vault modules |
| Pact JVM | 4.6.x | Consumer-driven contract testing |
| Cypress | 14.x | E2E for Angular operator console |
| k6 | 0.57.x | Load testing |

## Build & Quality

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| Maven | 3.9.9 | Use Maven Wrapper (mvnw) |
| Spotless | 2.45.x | Code formatting |
| Checkstyle | 10.x | Via maven-checkstyle-plugin |
| PMD | 7.x | Via maven-pmd-plugin |
| SpotBugs | 4.9.x | Via spotbugs-maven-plugin |
| ArchUnit | 1.4.x | Architecture boundary enforcement |
| JaCoCo | 0.8.x | Coverage gating; min 80% line/branch |
| SonarQube scanner | 5.x | Wired into CI |
| OWASP Dependency-Check | 12.x | |
| Trivy | 0.63.x | Container + filesystem vulnerability scan |
| Gitleaks | 8.x | Secret scanning in CI pre-commit |

## Infrastructure

| Component | Pinned Version | Notes |
|-----------|---------------|-------|
| Docker Engine | 27.x | |
| Kubernetes | 1.32.x | EKS managed node groups |
| Helm | 3.17.x | |
| Terraform | 1.12.x | AWS provider ~> 5.0 |
| Argo CD | 2.14.x | GitOps delivery |
| Argo Rollouts | 1.8.x | Canary deployments |

## Compatibility Notes

- **Spring Boot 4.0.6 + Spring Cloud 2025.0.1**: Boot 4.x requires Spring Cloud 2025.x BOM. The older `2023.x` / `2022.x` BOMs are NOT compatible.
- **Kafka 4.3 + Schema Registry 7.9.x**: Confluent Platform 7.9 ships Kafka 3.9 internally but the clients are wire-compatible with Kafka 4.x. Pin `kafka-avro-serializer:7.9.1` and set `schema.registry.url` explicitly.
- **Debezium 3.1.x + PostgreSQL 16**: Requires `wal_level = logical` in PostgreSQL config. Set this via the docker-compose environment and in the RDS parameter group.
- **Flyway 11.x**: The `flyway-database-postgresql` module must be added explicitly when using Boot 4.x; the auto-configuration no longer bundles it.
- **Angular 21 + NgRx 19**: NgRx 19+ uses the Signals Store which requires Angular 17+; Angular 21 signals are stable and zoneless change detection is the recommended default.
