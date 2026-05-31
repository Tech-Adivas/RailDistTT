# System Architecture

## Overview

The Railway Timetable Distribution Platform is an event-driven, CQRS microservices system. Operators author and approve timetables via a central console; changes propagate in ≤5 s (p95) to passenger apps, station displays, and partner feeds through a Kafka backbone.

The two central reliability guarantees are:
1. **Zero acknowledged-write loss** — the Transactional Outbox pattern ensures a timetable write is either fully committed (including its Kafka event) or fully rolled back. There is no window where a state change commits but its event is lost.
2. **Idempotent consumers** — every downstream consumer deduplicates on `eventId`, so Kafka's at-least-once delivery never causes double-processing.

---

## High-Level Component Diagram

```mermaid
flowchart TD
  subgraph Client Tier
    UI[Angular 21 Operator Console]
    PA[Passenger Apps / External]
    SD[Station Displays]
    PF[Partner Feeds]
  end

  subgraph Gateway Tier
    GW[API Gateway\nSpring Cloud Gateway\nOIDC · RBAC · Rate Limit · Circuit Breaker]
  end

  subgraph Write Side
    TT[Timetable Service\nDDD Aggregates · Approval · Audit]
    OB[(Outbox Table\natomic with state)]
    DBZ[Debezium CDC\nOutbox Relay]
  end

  subgraph Kafka Backbone
    K{{Apache Kafka 4.3\nKRaft Mode}}
    SR[Schema Registry\nAvro BACKWARD compat]
    DLQ[(Dead Letter Queue)]
  end

  subgraph Read Side
    SCH[Schedule Service\nCompute Effective Schedules]
    QP[Query Projector\nEvent → Read Model]
    QS[Query Service\nCQRS Read API]
    RM[(Read Model\nPostgreSQL + Redis cache)]
  end

  subgraph Distribution
    DIST[Distribution Service\nFan-out · WebSocket · Saga]
    NOT[Notification Service\nPush · SMS · Email\nIdempotent]
  end

  subgraph Secrets
    VAULT[HashiCorp Vault]
  end

  subgraph Observability
    PROM[Prometheus]
    GRAFANA[Grafana · Loki · Tempo]
  end

  UI --> GW
  PA --> GW
  GW --> TT
  GW --> QS
  TT --> OB
  OB --> DBZ
  DBZ --> K
  K --> SCH
  SCH --> K
  K --> QP
  QP --> RM
  QS --> RM
  K --> DIST
  DIST -->|WebSocket/STOMP| UI
  DIST --> SD
  DIST --> PF
  K --> NOT
  NOT --> PA
  K --> DLQ
  VAULT -.->|secrets| GW
  VAULT -.->|secrets| TT
  VAULT -.->|secrets| SCH
  VAULT -.->|secrets| QS
  VAULT -.->|secrets| DIST
  VAULT -.->|secrets| NOT
  PROM -.->|scrape /actuator/prometheus| GW
  PROM -.->|scrape| TT
  PROM -.->|scrape| SCH
  PROM -.->|scrape| QS
  PROM -.->|scrape| DIST
  PROM -.->|scrape| NOT
  GRAFANA -.-> PROM
```

---

## Transactional Outbox Pattern

The single most important reliability mechanism in the system. Without it, a service could commit a database write but fail before publishing the Kafka event, leaving state and events permanently inconsistent.

```mermaid
sequenceDiagram
  actor Operator
  participant API as Timetable Service API
  participant DB as PostgreSQL
  participant DBZ as Debezium CDC
  participant K as Kafka

  Operator->>API: PATCH /timetables/{id}/approve
  API->>DB: BEGIN TRANSACTION
  API->>DB: UPDATE timetable SET status='APPROVED'
  API->>DB: INSERT INTO outbox(aggregate_type, aggregate_id, event_type, payload)
  API->>DB: COMMIT
  Note over DB: Both rows committed atomically.<br/>If either fails, both roll back.
  API-->>Operator: 200 OK (acknowledged)

  loop Continuous CDC poll (< 500 ms lag)
    DBZ->>DB: Read WAL (logical replication)
    DBZ->>K: Publish timetable.changed event
    Note over DBZ,K: Only runs after commit — no phantom events.
  end
```

---

## CQRS Event Flow

```mermaid
sequenceDiagram
  participant K as Kafka
  participant SCH as Schedule Service
  participant QP as Query Projector
  participant REDIS as Redis Cache
  participant QS as Query Service
  participant UI as Operator Console

  K->>SCH: timetable.changed (eventId, payload)
  Note over SCH: Deduplicate on eventId (idempotent)
  SCH->>SCH: Compute effective schedules
  SCH->>K: schedule.computed

  K->>QP: schedule.computed (eventId)
  Note over QP: Deduplicate on eventId
  QP->>QP: Project into read model
  QP->>REDIS: EVICT stale cache keys

  UI->>QS: GET /schedules/{id}
  QS->>REDIS: Cache lookup
  alt Cache hit
    REDIS-->>QS: Cached schedule
  else Cache miss
    QS->>QS: Query read-model DB
    QS->>REDIS: Populate cache
  end
  QS-->>UI: Schedule response
```

---

## Approval Workflow State Machine

```mermaid
stateDiagram-v2
  [*] --> DRAFT : Create timetable
  DRAFT --> PENDING_REVIEW : Submit for review
  PENDING_REVIEW --> DRAFT : Request changes
  PENDING_REVIEW --> APPROVED : Approve
  PENDING_REVIEW --> REJECTED : Reject
  APPROVED --> ACTIVE : Activate (effective date reached)
  ACTIVE --> SUPERSEDED : Newer timetable activated
  DRAFT --> CANCELLED : Cancel
  PENDING_REVIEW --> CANCELLED : Cancel
```

---

## Track Maintenance Integration

Railway track maintenance windows affect active timetables. When a maintenance window is created or modified, the system:

1. Checks all `ACTIVE` timetables for affected train paths.
2. Emits a `maintenance.window.created` event.
3. Schedule Service computes an emergency/modified schedule.
4. Distribution Service pushes updates to all channels.
5. Notification Service alerts affected passengers and operators.

```mermaid
flowchart LR
  MW[Maintenance Window\nCreated/Updated] --> TT
  TT -->|maintenance.window.created| K{{Kafka}}
  K --> SCH[Schedule Service\nCompute modified schedules]
  SCH --> K
  K --> DIST[Distribution Service]
  K --> NOT[Notification Service]
  DIST --> SD[Station Displays]
  DIST --> PA[Passenger Apps]
  NOT --> SMS[SMS Alerts]
  NOT --> PUSH[Push Notifications]
```

---

## Emergency Timetable Updates

Emergency updates bypass the normal approval workflow. An operator with `EMERGENCY_OPERATOR` role can publish directly to `ACTIVE` status. The system:

1. Records the emergency override in the immutable audit log with mandatory justification.
2. Skips `PENDING_REVIEW` state; goes `DRAFT → EMERGENCY_ACTIVE`.
3. All downstream events are marked `priority=EMERGENCY` so consumers and distribution channels prioritise them.
4. Post-hoc review is required within 24 hours; a reminder notification is scheduled.

---

## Correlation ID Propagation Chain

The correlation ID ensures a single user request can be traced across every service and Kafka hop.

```
Browser → X-Correlation-Id header
  → API Gateway (mints if absent, propagates)
    → Service MDC (logged on every line)
      → Kafka record header: correlationId
        → Consumer MDC (restored from header)
          → Response header back to browser
```

All structured log lines include `"correlationId": "..."`. OpenTelemetry trace context is propagated in parallel via W3C `traceparent` header and Kafka record headers.

---

## Failure Modes and Mitigations

| Failure | Detection | Mitigation |
|---------|-----------|-----------|
| DB write fails | Transaction rollback | Outbox row also rolled back; no phantom event |
| Debezium lag / crash | Kafka consumer lag alert | Debezium resumes from last WAL position; no event loss |
| Kafka broker unavailable | Outbox relay retry | Debezium retries with backoff; at-most 30 s window before alert |
| Consumer processing error (transient) | Exception caught | Exponential backoff retry (3 attempts); then → DLQ |
| Consumer processing error (poison) | Deserialization / invariant failure | Straight to DLQ; alert fires; manual triage |
| Redis cache unavailable | Lettuce connection exception | Fall through to DB; degrade gracefully |
| DB primary failover | HikariCP connection error | Multi-AZ RDS; HikariCP reconnects within 30 s |
| Read model corrupted | Data inconsistency detected | Replay events from Kafka (retention ≥ 7 days) to rebuild |
| API Gateway circuit open | Resilience4j state: OPEN | Return 503 + Retry-After; fallback controller serves last cached |
| WebSocket disconnect | Client-side event | Auto-reconnect with exponential backoff; refetch on resume |

---

## Security Architecture

- **Authentication**: OIDC via external IdP (e.g. AWS Cognito). JWT validated at the gateway.
- **Authorisation**: RBAC roles (`TIMETABLE_AUTHOR`, `TIMETABLE_APPROVER`, `EMERGENCY_OPERATOR`, `ADMIN`, `READ_ONLY`). Enforced at gateway and at service method level (`@PreAuthorize`).
- **Secrets**: HashiCorp Vault. No secret in code, config files, or images. Spring Cloud Vault auto-renews leases.
- **Transport**: TLS 1.3 everywhere. mTLS within the cluster (service mesh or Istio).
- **Data at rest**: RDS storage encrypted (AES-256). Kafka topic encryption at MSK. Redis AUTH + TLS.
- **Audit**: Every state-changing operation recorded in append-only `audit_log` table with actor, timestamp, before/after state, and justification for overrides.

---

## Data Retention

| Data | Retention | Justification |
|------|-----------|--------------|
| Kafka events (all topics) | 7 days | Enables full read-model replay from recent history |
| Audit log (DB) | Indefinite | Regulatory / operational requirement |
| Timetable history (DB) | 2 years | Operational reference; archived after 90 days |
| Read model snapshots | 30 days | Point-in-time debugging |
| Logs (Loki) | 30 days | Operational debugging |
| Traces (Tempo) | 7 days | Performance analysis |
| Metrics (Prometheus) | 15 days | SLO reporting |
