# Railway Timetable Distribution Platform — Developer Guide

## Quick orientation

The platform is an event-driven, CQRS microservices system for authoring, approving, and distributing railway timetables. Operators create and approve timetables via the Angular Operator Console; state changes propagate in ≤5 s (p95) through a Kafka backbone to the read model, station displays, passenger apps, and partner feeds. Six Java (Spring Boot 4) services own distinct bounded contexts; the write side uses the Transactional Outbox pattern for zero-acknowledged-write-loss, and every consumer is idempotent on `eventId`.

---

## Repo layout

```
RailDistTT/
├── services/                  # Six Spring Boot microservices
│   ├── api-gateway/           # Spring Cloud Gateway — OIDC, RBAC, rate limit, circuit breaker (port 8080)
│   ├── timetable-service/     # Write side — DDD aggregates, approval state machine, audit log (port 8081)
│   ├── schedule-service/      # Computes effective schedules from timetable events (port 8082)
│   ├── query-service/         # CQRS read API — serves query projections from read model (port 8083)
│   ├── distribution-service/  # Fan-out saga — WebSocket, station displays, partner feeds (port 8084)
│   └── notification-service/  # Push, SMS, email notifications; idempotent (port 8085)
├── shared/
│   ├── common-lib/            # Shared: correlation IDs, exception handling, Kafka health indicator
│   └── events/                # Avro schemas + generated POJOs + Topics constants
├── frontend/
│   └── operator-console/      # Angular 21 operator UI (proxies /api and /ws to api-gateway:8080)
├── infra/
│   ├── helm/                  # Helm charts — one chart per service plus platform chart
│   ├── terraform/             # EKS, MSK, RDS, ECR, IAM, VPC provisioning
│   ├── observability/         # Prometheus config, Grafana dashboards, alert rules, Loki, Tempo
│   ├── debezium/              # Debezium connector config for Transactional Outbox CDC
│   ├── vault/                 # Vault policies and dev-secrets seed script
│   └── postgres/              # Local PostgreSQL init scripts
├── docs/
│   ├── architecture.md        # Deep-dive architecture with component and sequence diagrams
│   ├── CONFIGURATION.md       # Every PLACEHOLDER_* value and how to fill it
│   ├── adr/                   # Architecture Decision Records (ADR-001 through ADR-008)
│   └── runbooks/              # Operational playbooks (DLQ, lag, emergency activation, failover, deploy)
├── docker-compose.yml         # Full local infrastructure stack
├── Makefile                   # Developer task runner (see below)
└── pom.xml                    # Maven multi-module root (Java 21, Spring Boot 4)
```

---

## Local development

### Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Maven 3.9+ (or use the included `./mvnw` wrapper)
- Node 22 LTS + npm
- Docker Desktop 4.x+ (Docker Engine 27+, Compose v2)
- `make`

### Bring up the local infrastructure stack

```bash
make up
```

Starts and health-checks: PostgreSQL 16, Kafka (KRaft, no Zookeeper), Schema Registry, Redis, HashiCorp Vault (dev mode), Debezium, Prometheus, and Grafana.

After `make up` completes, the following local endpoints are available:

| Service | URL |
|---|---|
| Kafka broker | `localhost:29092` |
| Schema Registry | `http://localhost:8081` |
| PostgreSQL | `localhost:5432` |
| Vault UI | `http://localhost:8200` (token: `dev-only-root-token`) |
| Grafana | `http://localhost:3000` |

To also start all Spring Boot services in background dev mode:

```bash
make dev
```

### Run a single service (recommended for development)

```bash
cd services/timetable-service
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` Spring profile uses hardcoded `localhost` URLs (PostgreSQL, Kafka, Schema Registry) and bypasses Vault — all defined as `spring.config.activate.on-profile: local` in each service's `application.yml`.

### Run the frontend

```bash
cd frontend/operator-console
npm ci
npm start          # serves on http://localhost:4200; proxies /api and /ws to localhost:8080
```

---

## Building

### All services (from repo root)

```bash
mvn clean verify   # compiles, runs unit tests + integration tests (requires Docker for Testcontainers)
```

Or via the Makefile:

```bash
make test          # unit + integration tests
make test-unit     # unit tests only (mvn test)
make test-integration  # integration tests only (-Dgroups=integration)
make build         # compile + package, skip tests
```

### Single service or module

```bash
# Build and test one service (also builds shared dependencies)
mvn -pl services/timetable-service -am clean verify

# Build only the events module (re-generates Avro POJOs)
mvn -pl shared/events -am clean generate-sources
```

Or via the Makefile:

```bash
make generate-events   # re-generates Java POJOs from all Avro schemas in shared/events/
```

### Docker images (requires ECR login)

```bash
docker build -f services/timetable-service/Dockerfile \
  -t timetable-service:local .
```

All Dockerfiles use multi-stage builds. The build stage compiles with Maven; the runtime stage is Eclipse Temurin 21 JRE on Alpine.

---

## Testing

```bash
mvn test                 # unit tests only (no Docker required)
mvn verify               # unit + integration tests (Testcontainers pulls real Kafka, PostgreSQL images)
```

Integration tests are in `src/test/java` alongside unit tests but annotated with `@Tag("integration")`. They use the `application-test.yml` profile which connects to Testcontainers-managed containers — not the `make up` stack. You do not need `make up` running to run integration tests.

---

## Key patterns to follow

### Adding a new command to timetable-service

1. Add a command record in `application/command/` (e.g. `MyNewCommand.java`)
2. Add a handler method in `TimetableCommandHandler` annotated with `@Transactional` and `@Retryable(retryFor = {TransientDataAccessException.class, CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))`
3. Add the corresponding domain method on `Timetable` (in `domain/model/`) that enforces the state transition via `ApprovalStateMachine`
4. If the command produces a new event type, add an Avro schema in `shared/events/src/main/avro/`, run `make generate-events`, and define the topic constant in `Topics.java`
5. Publish via `OutboxEventWriter` (writes to the `outbox_events` table atomically in the same transaction — do NOT call Kafka directly from the command handler)
6. Add an `@Timed` annotation: `@Timed(value = "timetable.command.duration", extraTags = {"command", "my-new-command"})`
7. Add a REST endpoint in `TimetableController` with the appropriate `@PreAuthorize` role check
8. Write unit tests for the domain model and integration tests for the full command handler → outbox flow

### Secrets

**NEVER** put secrets in code, configuration files, or Git commits.

All secrets are stored in HashiCorp Vault and injected into pods at runtime by the Vault Agent Sidecar Injector as environment properties.

- **Local dev:** `infra/vault/seed-dev-secrets.sh` seeds dummy (non-real) development values into the Vault dev instance started by `make up`. These are for local use only and must never be used in any deployed environment.
- **Adding a new secret:** (1) Add the real value to Vault at the appropriate path (`secret/data/railway/<service>/<key>`). (2) Reference it in `application.yml` using the Spring Vault placeholder convention (`${vault.secret.<key>}`). (3) Add a `PLACEHOLDER_*` comment with a `# TODO(config): Vault path: ...` annotation. (4) Document it in `docs/CONFIGURATION.md`.

See `ADR-006-vault-secrets-strategy.md` for the full decision record.

### Adding a new Kafka consumer

1. Add a `@KafkaListener` method in a `@Component` class. Inject and use the service's `ConcurrentKafkaListenerContainerFactory` bean (define a new factory bean in `KafkaConsumerConfig` if consuming a new event type).
2. Inject `ProcessedEventRepository` and check-then-save the `eventId` at the start of the handler to ensure idempotency. The `processed_events.event_id` column has a UNIQUE constraint — a duplicate will throw `DataIntegrityViolationException` which you should catch and silently acknowledge.
3. Configure DLQ routing by registering your non-retryable exception classes in the service's `KafkaConsumerConfig.errorHandler()` bean (extends `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`).
4. Add a `ProcessedEventsCleanupJob` scheduled task (see existing examples in each service's `infrastructure/maintenance/` package) to periodically delete old rows from `processed_events`.

### Schema changes (Avro)

All schema changes must maintain **BACKWARD** compatibility — new consumers must be able to read records written by older producers. This is enforced in CI by the Schema Registry Maven plugin configured in `shared/events/pom.xml`. PRs with incompatible schema changes will fail CI.

See `ADR-004-avro-backward-compatibility.md`.

---

## Service ports (local)

| Service | App port | Management / Actuator port |
|---|---|---|
| api-gateway | 8080 | 8090 |
| timetable-service | 8081 | 8090 |
| schedule-service | 8082 | 8090 |
| query-service | 8083 | 8090 |
| distribution-service | 8084 | 8090 |
| notification-service | 8085 | 8090 |

Actuator endpoints exposed on port 8090: `health`, `readiness`, `liveness`, `prometheus`, `info`, `metrics`.

Kubernetes liveness and readiness probes use port 8090. Prometheus scrapes `/actuator/prometheus` on port 8090.

---

## Placeholder convention

All environment-specific values that are not secrets use the pattern:

```
PLACEHOLDER_<DESCRIPTIVE_NAME>
```

with an inline comment:

```yaml
# TODO(config): Vault path: secret/data/railway/<service>/<key>
# or
# TODO(config): Terraform output: <output_name>
```

See `docs/CONFIGURATION.md` for the complete reference of every placeholder, its source, and how to fill it for a real environment.

---

## Branch and CI

- **Feature branches** → PR → review → squash merge to `main`
- **`ci-backend.yml`** — triggered on changes to `services/**`, `shared/**`, `pom.xml`; runs Maven build matrix (all 6 services in parallel); pushes Docker images to ECR tagged with the git SHA
- **`ci-frontend.yml`** — triggered on changes to `frontend/**`; runs `ng build` and `ng test`
- **`cd-deploy.yml`** — auto-deploys to `dev` on every `main` push; manual workflow dispatch required for `prod` (requires GitHub Environment approval); deploys all services sequentially with `--atomic --wait --timeout 5m`
- **`security-scan.yml`** — runs weekly and on every PR; OWASP dependency check, Trivy image scanning, TruffleHog secret scanning

---

## Grafana dashboards

| Dashboard | URL slug | What it shows |
|---|---|---|
| platform-overview | `platform-overview` | Error rates, latency, deployment events — start here |
| kafka-consumer-lag | `kafka-consumer-lag` | Per-group, per-topic consumer lag |
| timetable-service | `timetable-service` | Command latency, HikariCP pool, approval counters |
| jvm-overview | `jvm-overview` | Heap, GC pause, thread counts for all services |

---

## Documentation

- `docs/architecture.md` — deep-dive architecture with Mermaid component and sequence diagrams
- `docs/CONFIGURATION.md` — every `PLACEHOLDER_*` value and how to fill it
- `docs/adr/` — architecture decision records (ADR-001 through ADR-008)
- `docs/runbooks/` — operational playbooks:
  - `dlq-triage.md` — handling DLQ messages
  - `kafka-consumer-lag.md` — responding to consumer lag alerts
  - `emergency-activation.md` — emergency timetable activation procedure
  - `database-failover.md` — RDS PostgreSQL failover
  - `deployment.md` — deploying a new version
