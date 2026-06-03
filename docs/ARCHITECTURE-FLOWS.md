# Railway Timetable Distribution Platform — Technical Architecture Flows

All diagrams use plain ASCII/Unicode box-drawing characters. No special renderer required.
Renders correctly in any terminal, plain-text viewer, or GitHub Markdown preview.

Every diagram maps directly to code in this repository. File paths are relative to the repo root.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Layer 0 — Shared Foundation](#2-layer-0--shared-foundation)
3. [Layer 1 — Edge: API Gateway](#3-layer-1--edge-api-gateway)
4. [Layer 2 — Write Side: Timetable Service](#4-layer-2--write-side-timetable-service)
5. [Layer 3 — CDC Transport: Debezium → Kafka](#5-layer-3--cdc-transport-debezium--kafka)
6. [Layer 4 — Compute Side: Schedule Service](#6-layer-4--compute-side-schedule-service)
7. [Layer 5 — Read Side: Query Service](#7-layer-5--read-side-query-service)
8. [Layer 6 — Distribution / Fan-Out: Distribution Service](#8-layer-6--distribution--fan-out-distribution-service)
9. [Layer 7 — Notifications: Notification Service](#9-layer-7--notifications-notification-service)
10. [Layer 8 — Observability Stack](#10-layer-8--observability-stack)
11. [Layer 9 — Infrastructure: Helm + Terraform + CI/CD](#11-layer-9--infrastructure-helm--terraform--cicd)
12. [End-to-End Flow: Timetable Change to Passenger Notification](#12-end-to-end-flow-timetable-change-to-passenger-notification)
13. [End-to-End Flow: Emergency Activation](#13-end-to-end-flow-emergency-activation)
14. [Appendix: Idempotency Pattern](#14-appendix-idempotency-pattern-all-consumers)

---

## 1. System Overview

```
╔══════════════════════════════════════════════════════════════════════════════════════╗
║                    RAILWAY TIMETABLE DISTRIBUTION PLATFORM                         ║
╚══════════════════════════════════════════════════════════════════════════════════════╝

 ┌─────────────────────────────────┐
 │         FRONTEND                │
 │  ┌───────────────────────────┐  │
 │  │    Operator Console       │  │
 │  │    Angular 21 · NgRx      │  │
 │  │    STOMP WebSocket client │  │
 │  └──────────┬────────────────┘  │
 └─────────────┼───────────────────┘
               │ HTTPS /api          WSS /ws (STOMP)
               ▼                    ↕
 ┌─────────────────────────────────────────────────────────────────────────────────┐
 │  EDGE LAYER                                                                     │
 │  ┌───────────────────────────────────────────────────────────────────────────┐  │
 │  │  API Gateway :8080                                                        │  │
 │  │  Spring Cloud Gateway · OIDC · RBAC · Rate Limit · Circuit Breaker        │  │
 │  └────────┬───────────────────────────────────────────────────────┬──────────┘  │
 └───────────┼───────────────────────────────────────────────────────┼─────────────┘
             │ POST/PATCH                                             │ GET / WS upgrade
             ▼                                                        ▼
 ┌────────────────────────┐                             ┌────────────────────────┐
 │  WRITE LAYER           │                             │  READ LAYER            │
 │ ┌────────────────────┐ │                             │ ┌────────────────────┐ │
 │ │ Timetable Service  │ │                             │ │  Query Service     │ │
 │ │ :8081              │ │                             │ │  :8083             │ │
 │ │ DDD Aggregate      │ │                             │ │  CQRS projector    │ │
 │ │ Approval state     │ │                             │ │  Redis cache-aside │ │
 │ │ machine            │ │                             │ │  REST API          │ │
 │ │ Transactional      │ │                             │ └──────────┬─────────┘ │
 │ │ Outbox             │ │                             │            │ ↕         │
 │ └────────┬───────────┘ │                             │ ┌──────────▼─────────┐ │
 │          │ ACID tx     │                             │ │ Redis / ElastiCache│ │
 │ ┌────────▼───────────┐ │                             │ └────────────────────┘ │
 │ │ PostgreSQL         │ │                             └────────────────────────┘
 │ │ timetable_db       │ │
 │ │ outbox_events      │ │
 │ │ audit_log          │ │
 │ └────────┬───────────┘ │
 └──────────┼─────────────┘
            │ WAL / pgoutput
            ▼
 ┌───────────────────────────────────────────────────────────────────────────────────┐
 │  EVENT BUS                                                                        │
 │  ┌────────────────┐   ┌──────────────────────────────────────────────────────┐   │
 │  │  Debezium CDC  │──▶│  Kafka / MSK  (KRaft 3.7, no ZooKeeper)             │   │
 │  │  pgoutput→Avro │   │                                                      │   │
 │  └────────────────┘   │  Topics:                                             │   │
 │                        │  ├─ railway.timetable.changed                       │   │
 │  ┌────────────────┐    │  ├─ railway.schedule.computed                       │   │
 │  │ Schema Registry│◀──▶│  ├─ railway.distribution.events                    │   │
 │  │ Avro BACKWARD  │   │  ├─ railway.notification.requests                   │   │
 │  │ compat (CI)    │   │  ├─ railway.maintenance.windows                     │   │
 │  └────────────────┘   │  └─ railway.dlq                                     │   │
 │                        └──────────┬──────────────────────────────────────────┘   │
 └─────────────────────────────────── ┼─────────────────────────────────────────────┘
                                      │
            ┌─────────────────────────┼────────────────────────────┐
            │                         │                            │
            ▼                         ▼                            ▼
 ┌──────────────────┐      ┌──────────────────┐        ┌──────────────────────┐
 │  COMPUTE LAYER   │      │  DISTRIBUTION    │        │  NOTIFICATION        │
 │ ┌──────────────┐ │      │  LAYER           │        │  LAYER               │
 │ │Schedule Svc  │ │      │ ┌──────────────┐ │        │ ┌──────────────────┐ │
 │ │:8082         │ │      │ │Distribution  │ │        │ │Notification Svc  │ │
 │ │Kafka consumer│ │      │ │Service :8084 │ │        │ │:8085             │ │
 │ │Transactional │─┼─────▶│ │Fan-out Saga  │ │        │ │FCM · SMS · Email │ │
 │ │publish       │ │      │ │WebSocket/STOMP│ │       │ └──────────────────┘ │
 │ └──────────────┘ │      │ └──────────────┘ │        └──────────────────────┘
 └──────────────────┘      └──────────────────┘
            │                         │
            └─────────────────────────┘
                          │ All services → OTLP traces
                          ▼
 ┌─────────────────────────────────────────────────────────────────────────────────┐
 │  OBSERVABILITY                                                                  │
 │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌───────────────────────────┐ │
 │  │ Prometheus │  │   Loki     │  │   Tempo    │  │  Grafana 12.x Dashboards  │ │
 │  │ scrape     │  │ log aggr.  │  │ dist.traces│  │  platform-overview        │ │
 │  │ :8090/     │  │            │  │ W3C        │  │  kafka-consumer-lag       │ │
 │  │ actuator/  │  │            │  │ traceparent│  │  timetable-service        │ │
 │  │ prometheus │  │            │  │            │  │  jvm-overview             │ │
 │  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └───────────┬───────────────┘ │
 │        └───────────────┴────────────────┴────────────────────▶│               │ │
 │                                                                └───────────────┘ │
 └─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Layer 0 — Shared Foundation

### 2.1 Shared Events (`shared/events/`)

#### Avro Schema Files → Topics Mapping

```
  shared/events/src/main/avro/                      shared/events/src/main/java/.../Topics.java
  ────────────────────────────────                  ─────────────────────────────────────────
  ┌──────────────────────────────┐                  ┌──────────────────────────────────────┐
  │ common.avsc                  │                  │  TIMETABLE_CHANGED                   │
  │  EventMetadata (embedded)    │                  │  = "railway.timetable.changed"        │
  │  - eventId (UUID)            │                  ├──────────────────────────────────────┤
  │  - eventType (string)        │                  │  SCHEDULE_COMPUTED                   │
  │  - occurredAt (timestamp)    │                  │  = "railway.schedule.computed"        │
  │  - correlationId (string)    │                  ├──────────────────────────────────────┤
  │  - actor (string)            │                  │  DISTRIBUTION_EVENTS                 │
  │  - schemaVersion (int)       │                  │  = "railway.distribution.events"      │
  └──────────────────────────────┘                  ├──────────────────────────────────────┤
                                                     │  NOTIFICATION_REQUESTS               │
  ┌──────────────────────────────┐                  │  = "railway.notification.requests"    │
  │ timetable-changed-event.avsc │──────────────▶   ├──────────────────────────────────────┤
  │  TimetableChangedEvent       │   TIMETABLE_     │  MAINTENANCE_WINDOWS                 │
  │  - metadata (EventMetadata)  │   CHANGED        │  = "railway.maintenance.windows"      │
  │  - changeType (enum)         │                  ├──────────────────────────────────────┤
  │  - timetableId (UUID)        │                  │  DLQ                                 │
  │  - lineId (string)           │                  │  = "railway.dlq"                     │
  │  - effectiveDate (date)      │                  │  (original-topic header preserved)    │
  │  - expiryDate (date)         │                  └──────────────────────────────────────┘
  └──────────────────────────────┘

  changeType enum values:
  CREATED · UPDATED · SUBMITTED_FOR_REVIEW · APPROVED · REJECTED
  ACTIVATED · SUPERSEDED · CANCELLED · EMERGENCY_ACTIVATED
  MAINTENANCE_WINDOW_APPLIED

  ┌──────────────────────────────┐
  │ schedule-computed-event.avsc │──────────────▶  SCHEDULE_COMPUTED
  │  ScheduleComputedEvent       │
  │  - metadata (EventMetadata)  │
  │  - timetableId (UUID)        │
  │  - lineId (string)           │
  │  - services (list)           │
  │  - triggeringEventId (UUID)  │
  └──────────────────────────────┘

  ┌──────────────────────────────┐
  │ distribution-event.avsc      │──────────────▶  DISTRIBUTION_EVENTS
  │  DistributionEvent           │
  │  - metadata (EventMetadata)  │
  │  - channel (enum)            │
  │  - status (enum)             │
  │  - failureReason (string?)   │
  └──────────────────────────────┘

  ┌──────────────────────────────┐
  │ notification-request-        │──────────────▶  NOTIFICATION_REQUESTS
  │ event.avsc                   │
  │  NotificationRequestEvent    │
  │  - metadata (EventMetadata)  │
  │  - recipientIds (list)       │
  │  - channels (list)           │
  │  - template (string)         │
  │  - templateVars (map)        │
  │  - isEmergency (boolean)     │
  └──────────────────────────────┘

  ┌──────────────────────────────┐
  │ maintenance-window-event.avsc│──────────────▶  MAINTENANCE_WINDOWS
  │  MaintenanceWindowEvent      │
  │  - metadata (EventMetadata)  │
  └──────────────────────────────┘

  Schema backward-compatibility enforced by Schema Registry Maven plugin in CI.
  PRs with incompatible schema changes fail CI. See ADR-004.
```

### 2.2 Common Library (`shared/common-lib/`) — Correlation ID Flow

```
  HTTP REQUEST PATH
  ─────────────────────────────────────────────────────────────────────
  Inbound HTTP Request
        │
        ▼
  ┌─────────────────────────────────────────┐
  │  CorrelationIdFilter  (OncePerRequest)  │
  │                                         │
  │  X-Correlation-Id header present?       │
  │    YES ──▶ use existing UUID            │
  │    NO  ──▶ mint new UUID v4             │
  │                                         │
  │  MDC.put("correlationId", id)           │
  │  CorrelationIdHolder.set(id)            │
  │  Echo X-Correlation-Id in response      │
  └────────────────────┬────────────────────┘
                       │ Continue filter chain
                       ▼
                  Application logic runs
                  (correlationId in MDC → every log line)
                       │
                       ▼
                  MDC.clear()  ← finally block

  KAFKA CONSUMER PATH
  ─────────────────────────────────────────────────────────────────────
  Inbound Kafka Record
        │
        ▼
  ┌─────────────────────────────────────────┐
  │  KafkaCorrelationIdPropagator           │
  │                                         │
  │  extract correlationId                  │
  │  from Kafka record headers              │
  │                                         │
  │  MDC.put("correlationId", id)           │
  │  CorrelationIdHolder.set(id)            │
  └────────────────────┬────────────────────┘
                       │
                       ▼
                  Consumer logic runs
                  (correlationId in MDC → every log line)
                       │
                       ▼
                  MDC.clear()  ← finally block
```

### 2.3 Global Exception Handler

```
  Exception thrown in @RestController
        │
        ├── DomainException?
        │       └──▶  HTTP 422 UNPROCESSABLE_ENTITY
        │             ApiError { code, message, correlationId }
        │             log at WARN
        │
        ├── MethodArgumentNotValidException?
        │       └──▶  HTTP 422
        │             ApiError { fieldErrors[] — per-field violations }
        │
        ├── ConstraintViolationException?
        │       └──▶  HTTP 422
        │             ApiError per constraint
        │
        ├── NotFoundException?
        │       └──▶  HTTP 404
        │             ApiError { message }
        │
        └── any other Exception
                └──▶  HTTP 500 INTERNAL_SERVER_ERROR
                      ApiError — no stack trace in response body
                      (security: stack trace in logs only)
                              │
                              ▼
                      Return ApiError JSON response
```

---

## 3. Layer 1 — Edge: API Gateway

Port: 8080 (app) · 8090 (actuator)
Key files: `services/api-gateway/src/main/java/com/railway/platform/gateway/config/`

### 3.1 Request Processing Pipeline

```
  Request arrives at AWS ALB
  (ACM TLS termination)
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │  Step 1 — JWT Validation                            │
  │  SecurityWebFilterChain                             │
  │  NimbusReactiveJwtDecoder                           │
  │  validate JWT signature + expiry                    │
  └───────────────────────────┬─────────────────────────┘
                              │
              JWT valid?  ────┤
              NO ──▶ 401 Unauthorized
              YES
                              │
                              ▼
  ┌─────────────────────────────────────────────────────┐
  │  Step 2 — RBAC                                      │
  │  JwtGrantedAuthoritiesConverter                     │
  │  map JWT "roles" claim → Spring ROLE_* authorities  │
  │                                                     │
  │  Path/Role matrix:                                  │
  │                                                     │
  │  /emergency-activate  → ROLE_EMERGENCY_OPERATOR     │
  │  /approve /reject     → ROLE_TIMETABLE_APPROVER     │
  │  /request-changes        or ROLE_ADMIN              │
  │  /create /submit      → ROLE_TIMETABLE_AUTHOR       │
  │  /cancel /update         or ROLE_ADMIN              │
  │  /actuator/health     → public (no auth)            │
  │  all other /api/**    → any authenticated user      │
  │                                                     │
  │  Role mismatch → 403 Forbidden                      │
  └───────────────────────────┬─────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────┐
  │  Step 3 — CQRS Routing  (GatewayRoutesConfig)       │
  │                                                     │
  │  HTTP GET?          → query-service:8083            │
  │  Path /ws/**?       → distribution-service:8084     │
  │                       (WebSocket upgrade,           │
  │                        bypass rate limiter)         │
  │  /emergency-act...? → timetable-service:8081        │
  │                       (emergency circuit breaker)   │
  │  POST/PATCH /api/** → timetable-service:8081        │
  │                       (write circuit breaker)       │
  └───────────────────────────┬─────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────┐
  │  Step 4 — Rate Limiting  (RedisRateLimiter)         │
  │                                                     │
  │  JWT present?                                       │
  │    YES → key = user:{JWT.sub}    (per-user bucket)  │
  │    NO  → key = ip:{X-Forwarded-For} (per-IP bucket) │
  │                                                     │
  │  Default: 100 req/s, burst 200                      │
  │  Tokens available?                                  │
  │    NO  → 429 Too Many Requests                      │
  │    YES → continue                                   │
  └───────────────────────────┬─────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────┐
  │  Step 5 — Circuit Breaker  (Resilience4j)           │
  │                                                     │
  │  Sliding window: 10 calls                           │
  │  Failure threshold: 50%                             │
  │                                                     │
  │  Circuit OPEN?                                      │
  │    YES → 503 → /fallback/{service}                  │
  │    NO  → continue                                   │
  └───────────────────────────┬─────────────────────────┘
                              │
                              ▼
  Forward to upstream service
  + X-Correlation-Id header
  + Authorization: Bearer {JWT}
```

---

## 4. Layer 2 — Write Side: Timetable Service

Port: 8081 (app) · 8090 (actuator)
Key files: `services/timetable-service/src/main/java/com/railway/platform/timetable/`

### 4.1 Domain Model — Approval State Machine

```
  ApprovalStateMachine.validateTransition() enforces all transitions.
  Any unlisted transition throws InvalidStateTransitionException.

                       create()
                  ROLE_TIMETABLE_AUTHOR
                          │
                          ▼
                    ┌─────────┐
                    │  DRAFT  │◀──────────────────────────────┐
                    └────┬────┘     requestChanges(reviewerId) │
                         │                                     │
           ┌─────────────┼─────────────┐                      │
           │             │             │                       │
           ▼             ▼             ▼                       │
      cancel()    submitForReview()  activateEmergency()       │
           │             │           actor, justification      │
           │             │           ROLE_EMERGENCY_OPERATOR   │
           │             ▼                                     │
           │      ┌──────────────┐                            │
           │      │PENDING_REVIEW│──────────────────────────▶─┘
           │      └──────┬───────┘     requestChanges
           │             │
           │   ┌─────────┼──────────────────────┐
           │   │         │                      │
           │   ▼         ▼                      ▼
           │ cancel() approve(reviewerId)    reject(reviewerId, reason)
           │           [reviewer ≠ author]       │
           │           ROLE_TIMETABLE_APPROVER   │
           │                 │                   │
           │                 ▼                   ▼
           │          ┌──────────┐         ┌──────────┐
           │          │ APPROVED │         │ REJECTED │──▶ [terminal]
           │          └────┬─────┘         └──────────┘
           │               │
           │        ┌──────┼────────────┐
           │        │      │            │
           │        ▼      ▼            ▼
           │   cancel()  activate()  activateEmergency()
           │           TimetableActivation     │
           │           Scheduler on            │
           │           effectiveDate           │
           │                 │                 │
           │                 ▼                 ▼
           │          ┌─────────┐   ┌──────────────────┐
           │          │ ACTIVE  │   │ EMERGENCY_ACTIVE  │
           │          └────┬────┘   └────────┬─────────┘
           │               │                 │
           │           supersede()       supersede()
           │               │                 │
           └──────────────▶┼◀────────────────┘
                           │
                           ▼
                    ┌───────────┐
                    │ SUPERSEDED│──▶ [terminal]
                    └───────────┘

           cancel() also available from PENDING_REVIEW and APPROVED
                           │
                           ▼
                    ┌───────────┐
                    │ CANCELLED │──▶ [terminal]
                    └───────────┘

  Status CHECK constraint values (PostgreSQL):
  DRAFT · PENDING_REVIEW · APPROVED · REJECTED · ACTIVE
  EMERGENCY_ACTIVE · SUPERSEDED · CANCELLED
```

### 4.2 Command Handler — Single ACID Transaction

```
  TimetableController
        │
        │  handleCommand(cmd, actor)
        ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │  TimetableCommandHandler                                                │
  │  @Transactional   @Retryable(retryFor={TransientDataAccessException,   │
  │                              CannotAcquireLockException},               │
  │                              maxAttempts=3,                             │
  │                              backoff=@Backoff(delay=100,               │
  │                                multiplier=2.0, maxDelay=1000))         │
  │                                                                         │
  │  1. TimetableRepository.findById(timetableId)                          │
  │       └──▶ SELECT * FROM timetables WHERE id=?  (loads @Version)       │
  │                                                                         │
  │  2. aggregate.domainMethod(params)   e.g. approve(reviewerId)          │
  │       └──▶ ApprovalStateMachine.validateTransition(current, target)    │
  │              ├── allowed  →  apply state + accumulate DomainEvent      │
  │              └── blocked  →  throws InvalidStateTransitionException     │
  │                                                                         │
  │  3. TimetableRepository.save(aggregate)                                 │
  │       └──▶ UPDATE timetables SET status=?, version=?                   │
  │              WHERE id=? AND version=?                                   │
  │              (optimistic lock — version mismatch → 409 Conflict)       │
  │                                                                         │
  │  4. OutboxEventWriter.write(domainEvents)                               │
  │       └──▶ INSERT INTO outbox_events                                   │
  │              (id, aggregate_type, aggregate_id, event_type,            │
  │               payload, correlation_id, relay_published=false)          │
  │                                                                         │
  │  5. AuditLogWriter.write(domainEvents, actor)                          │
  │       └──▶ INSERT INTO audit_log                                       │
  │              (before_status, after_status, actor,                      │
  │               justification, created_at)                               │
  │                                                                         │
  │  ┌──────────────────────────────────────────────────────────────────┐  │
  │  │  *** COMMIT ***                                                  │  │
  │  │  timetables + outbox_events + audit_log written atomically       │  │
  │  │  or all roll back together                                       │  │
  │  └──────────────────────────────────────────────────────────────────┘  │
  │                                                                         │
  │  6. aggregate.clearDomainEvents()                                       │
  │                                                                         │
  │  @Timed metrics emitted:                                                │
  │    timetable.command.duration { command="approve" }                     │
  │    timetable.approvals.total                                            │
  │    timetable.emergency_activations.total                                │
  └─────────────────────────────────────────────────────────────────────────┘
        │
        │  CommandResult / 200 OK
        ▼
  TimetableController  →  HTTP response to API Gateway
```

### 4.3 Transactional Outbox + Fallback Relay

```
  ┌──────────────────────────────────────────────────────┐
  │  SINGLE ACID TRANSACTION (timetable_db)              │
  │                                                      │
  │  ┌──────────────────┐   ┌────────────────────────┐  │
  │  │ timetables table │   │ outbox_events          │  │
  │  │ (state updated)  │   │ INSERT                 │  │
  │  └──────────────────┘   │ relay_published=false  │  │
  │                         └────────────┬───────────┘  │
  │  ┌──────────────────┐                │              │
  │  │ audit_log        │                │              │
  │  │ INSERT           │                │              │
  │  └──────────────────┘                │              │
  └─────────────────────────────────────┼──────────────┘
                                        │
              ┌─────────────────────────┼────────────────────────────┐
              │ (Happy path)            │            (Fallback path)  │
              ▼                         │                             │
  ┌───────────────────────┐            │   ┌──────────────────────┐  │
  │  Debezium CDC         │            │   │ OutboxFallbackRelay  │  │
  │  monitors WAL on      │            │   │ @Scheduled every 5m  │  │
  │  outbox_events        │            │   │                      │  │
  │  plugin: pgoutput     │            │   │ SELECT WHERE         │  │
  └───────────┬───────────┘            │   │ relay_published=false │  │
              │                         │   │ AND age > threshold  │  │
              ▼                         │   └──────────┬───────────┘  │
  ┌───────────────────────┐            │              │               │
  │  OutboxEventRouter    │            │              ▼               │
  │  transform            │            │   ┌──────────────────────┐  │
  │  aggregate_type=      │            │   │ relayKafkaTemplate   │  │
  │  "timetable"          │            │   │ .send()              │  │
  │  → topic:             │            │   │ non-transactional    │  │
  │  railway.timetable    │            │   │ producer             │  │
  │  .changed             │            │   └──────────┬───────────┘  │
  └───────────┬───────────┘            │              │               │
              │                         │              ▼               │
              └─────────────────────────┼──▶ ┌────────────────────┐  │
                                        │    │  Kafka             │  │
                                        │    │  railway.timetable │  │
                                        │    │  .changed          │  │
                                        │    │                    │  │
                                        │    │  key=aggregate_id  │  │
                                        │    │  headers:          │  │
                                        │    │  correlation_id    │  │
                                        │    │  event_type        │  │
                                        │    └────────────────────┘  │
                                        │              │               │
                                        │    UPDATE outbox_events     │
                                        │    SET relay_published=true  │
                                        └──────────────────────────────┘

  HEALTH MONITORING
  ─────────────────────────────────────────────────────────────
  OutboxLagHealthIndicator
  COUNT WHERE relay_published=false AND age > threshold
        │
        ├── count  = 0  →  Health UP   (normal)
        └── count  > 0  →  Health DOWN → K8s readiness probe fails
                                        → pod removed from load balancer
                                        → alerts fire
```

### 4.4 Database Schema (timetable-service)

```
  ┌───────────────────────────────────────────────────┐
  │  timetables                                       │
  ├───────────────────────────────────────────────────┤
  │  id              UUID          PK                 │
  │  line_id         VARCHAR       NOT NULL           │
  │  status          VARCHAR       CHECK constraint   │
  │  name            VARCHAR       NOT NULL           │
  │  description     TEXT                             │
  │  effective_date  DATE                             │
  │  expiry_date     DATE                             │
  │  author_id       VARCHAR                         │
  │  reviewer_id     VARCHAR                         │
  │  version         INT           NOT NULL (opt.lock)│
  │  created_at      TIMESTAMP     NOT NULL           │
  │  updated_at      TIMESTAMP                        │
  └───────────────────────┬───────────────────────────┘
                          │ 1
                          │
              ┌───────────┴──────────────┐
              │ 0..*                     │ 0..*
              ▼                          ▼
  ┌─────────────────────────────┐  ┌────────────────────────────────┐
  │  outbox_events              │  │  audit_log                     │
  ├─────────────────────────────┤  ├────────────────────────────────┤
  │  id              UUID   PK  │  │  id              UUID   PK     │
  │  aggregate_type  VARCHAR    │  │  aggregate_type  VARCHAR        │
  │    = 'timetable'            │  │  aggregate_id    UUID   FK     │
  │  aggregate_id    UUID   FK  │  │  event_type      VARCHAR        │
  │  event_type      VARCHAR    │  │  payload         TEXT           │
  │  payload         TEXT (JSON)│  │  actor           VARCHAR        │
  │  correlation_id  VARCHAR    │  │  before_status   VARCHAR        │
  │  relay_published BOOLEAN    │  │  after_status    VARCHAR        │
  │  created_at      TIMESTAMP  │  │  justification   TEXT           │
  └─────────────────────────────┘  │  created_at      TIMESTAMP     │
                                   └────────────────────────────────┘

  Status CHECK values for timetables.status:
  DRAFT · PENDING_REVIEW · APPROVED · REJECTED · ACTIVE
  EMERGENCY_ACTIVE · SUPERSEDED · CANCELLED
```

---

## 5. Layer 3 — CDC Transport: Debezium → Kafka

Config: `infra/debezium/application.properties`

```
  ┌───────────────────────────────────────────────────────────────────────┐
  │  PostgreSQL (RDS / local)                                             │
  │                                                                       │
  │  ┌────────────────────────────────┐                                   │
  │  │  outbox_events table           │                                   │
  │  │  (public schema)               │                                   │
  │  └────────────────┬───────────────┘                                   │
  │                   │ INSERT captured                                    │
  │                   ▼                                                    │
  │  ┌────────────────────────────────┐                                   │
  │  │  WAL replication slot          │                                   │
  │  │  plugin:     pgoutput          │                                   │
  │  │  wal_level:  logical           │                                   │
  │  └────────────────┬───────────────┘                                   │
  └───────────────────┼───────────────────────────────────────────────────┘
                      │ change event stream
                      ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │  Debezium Server 3.1.2                                                │
  │                                                                       │
  │  ┌──────────────────────────────────────────────┐                    │
  │  │  PostgresConnector (source connector)        │                    │
  │  │  table.include.list: public.outbox_events    │                    │
  │  │                                              │                    │
  │  │  Producer config:                            │                    │
  │  │    enable.idempotence = true                 │                    │
  │  │    acks = all                                │                    │
  │  │    retries = 10                              │                    │
  │  │    → effectively-once production             │                    │
  │  └────────────────────┬─────────────────────────┘                    │
  │                       │                                               │
  │                       ▼                                               │
  │  ┌──────────────────────────────────────────────┐                    │
  │  │  OutboxEventRouter transform                 │                    │
  │  │  routing field: aggregate_type               │                    │
  │  │  → topic: railway.${routedByValue}           │                    │
  │  │  e.g. aggregate_type="timetable"             │                    │
  │  │       → railway.timetable.changed            │                    │
  │  └────────────────────┬─────────────────────────┘                    │
  │                       │                                               │
  │                       ▼                                               │
  │  ┌──────────────────────────────────────────────┐                    │
  │  │  Header Mapper                               │                    │
  │  │  correlation_id → Kafka record header        │                    │
  │  │  event_type     → Kafka record header        │                    │
  │  │  created_at     → Kafka record header        │                    │
  │  └────────────────────┬─────────────────────────┘                    │
  └───────────────────────┼───────────────────────────────────────────────┘
                          │  key=aggregate_id  value=Avro payload
                          ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │  Kafka / MSK  (KRaft 3.7, no ZooKeeper)                              │
  │                                                                       │
  │  ┌──────────────────────────────┐                                    │
  │  │  railway.timetable.changed   │◀──── consumed by:                  │
  │  │  (3 partitions, RF=3)        │      Schedule Service :8082        │
  │  │                              │      Query Service    :8083        │
  │  └──────────────────────────────┘                                    │
  │  ┌──────────────────────────────┐                                    │
  │  │  railway.schedule.computed   │◀──── consumed by:                  │
  │  │                              │      Distribution Svc :8084        │
  │  └──────────────────────────────┘      Query Service   :8083        │
  │  ┌──────────────────────────────┐                                    │
  │  │  railway.notification.reqs   │◀──── consumed by:                  │
  │  │                              │      Notification Svc :8085        │
  │  └──────────────────────────────┘                                    │
  │  ┌──────────────────────────────┐                                    │
  │  │  railway.distribution.events │  (audit/tracking topic)            │
  │  └──────────────────────────────┘                                    │
  │  ┌──────────────────────────────┐                                    │
  │  │  railway.maintenance.windows │                                    │
  │  └──────────────────────────────┘                                    │
  │  ┌──────────────────────────────┐                                    │
  │  │  railway.dlq                 │  (dead letter queue)               │
  │  │  original-topic header       │                                    │
  │  │  preserved                   │                                    │
  │  └──────────────────────────────┘                                    │
  │                                                                       │
  │  ┌──────────────────────────────┐                                    │
  │  │  _debezium_offsets           │  offset commit/restore (10s flush) │
  │  │  _debezium_schema_history    │  DDL snapshots                     │
  │  └──────────────────────────────┘                                    │
  └──────────────────────────┬────────────────────────────────────────────┘
                             │ ↕ schema validation on every record
                             ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  Schema Registry  (Confluent)                                        │
  │  Avro schemas · BACKWARD compatibility · enforced by CI plugin       │
  └──────────────────────────────────────────────────────────────────────┘
```

---

## 6. Layer 4 — Compute Side: Schedule Service

Port: 8082 (app) · 8090 (actuator)
Key files: `services/schedule-service/src/main/java/com/railway/platform/schedule/`

```
  ┌──────────────────────────────────┐
  │  Kafka: railway.timetable.changed│
  └──────────────────┬───────────────┘
                     │ TimetableChangedEvent (Avro)
                     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  TimetableChangedConsumer                                            │
  │  @KafkaListener                                                      │
  │  @Transactional(kafkaTransactionManager)                             │
  │  ← DB writes + Kafka publish are atomic within one Kafka transaction │
  │                                                                      │
  │  Step 1 — Propagate correlation ID                                   │
  │    KafkaCorrelationIdPropagator                                      │
  │    extract correlationId from record headers                         │
  │    MDC.put("correlationId", id)                                      │
  │                                                                      │
  │  Step 2 — Idempotency check                                          │
  │    ProcessedEventRepository.existsByEventId(event.eventId)           │
  │    ┌── DUPLICATE ──▶ log WARN, ack offset, return                   │
  │    └── NEW ──▶ continue                                              │
  │                                                                      │
  │  Step 3 — Compute schedule                                           │
  │    ScheduleComputationService.compute(event, correlationId)          │
  │    @Timed("schedule.computation.duration")                           │
  │    returns ScheduleComputedEvent                                     │
  │                                                                      │
  │  Step 4 — Persist + publish (all within Kafka transaction)           │
  │    schedule_db.save(ComputedScheduleEntity)                          │
  │    ProcessedEventRepository.save({eventId})  ← UNIQUE constraint     │
  │      (concurrent duplicate → DataIntegrityViolationException         │
  │       → catch → treat as duplicate → safe to skip)                  │
  │    ScheduleComputedProducer.publish(ScheduleComputedEvent)           │
  │      → Kafka: railway.schedule.computed                              │
  │        header: correlation_id                                        │
  │                                                                      │
  │  Step 5 — Ack + cleanup                                              │
  │    ack Kafka offset                                                  │
  │    MDC.clear() in finally block                                      │
  └──────────────────────────────────────────────────────────────────────┘
                     │
                     ▼
  ┌──────────────────────────────────┐
  │  Kafka: railway.schedule.computed│
  └──────────────────────────────────┘

  ERROR HANDLING (DefaultErrorHandler)
  ─────────────────────────────────────────────────────────────
  Non-retryable exception  →  publish to railway.dlq immediately
  Transient exception      →  3× exponential backoff
                               100 ms → 200 ms → 400 ms
                               still failing → railway.dlq

  Resilience4j circuit breaker on Kafka producer:
    sliding-window = 5 calls
    failure threshold = 50%
    wait duration = 30 s
```

---

## 7. Layer 5 — Read Side: Query Service

Port: 8083 (app) · 8090 (actuator)
Key files: `services/query-service/src/main/java/com/railway/platform/query/`

### 7.1 Event Projection Flow

```
  ┌──────────────────────────────────┐
  │  Kafka: railway.timetable.changed│
  └──────────────────┬───────────────┘
                     │ TimetableChangedEvent (Avro)
                     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  TimetableProjector                                                  │
  │  @KafkaListener   @Transactional  (DB only — not a Kafka tx)        │
  │                                                                      │
  │  Step 1 — Idempotency check                                          │
  │    ProcessedEventRepository.existsByEventId(event.eventId)           │
  │    ┌── DUPLICATE ──▶ ack offset, return                             │
  │    └── NEW ──▶ continue                                              │
  │                                                                      │
  │  Step 2 — UPSERT read model                                          │
  │    TimetableReadModelRepository.findById(timetableId)                │
  │    TimetableReadModelRepository.save(entity)                         │
  │    ← INSERT ON CONFLICT DO UPDATE  (full state overwrite from event) │
  │                                                                      │
  │  Step 3 — Mark processed + evict cache                               │
  │    ProcessedEventRepository.save({eventId})                          │
  │    ack Kafka offset                                                  │
  │    TimetableCacheService.evict(timetableId)                          │
  │    ← evict AFTER ack → next read fetches fresh data from query_db    │
  │                                                                      │
  │    MDC.clear() in finally block                                      │
  └──────────────────────────────────────────────────────────────────────┘
```

### 7.2 Cache-Aside Read Path

```
  GET /api/v1/timetables/{id}
        │
        ▼
  TimetableQueryController  →  TimetableQueryService.getById(id)
        │
        ▼
  TimetableCacheService.get
  key = "timetable:{timetableId}"
  StringRedisTemplate JSON lookup
        │
        ├── CACHE HIT  ──▶  return TimetableView  (log DEBUG "cache hit")
        │
        └── CACHE MISS
                │
                ▼
          TimetableReadModelRepository.findById
          query_db PostgreSQL
                │
                ├── NOT FOUND  ──▶  throw NotFoundException  →  404
                │
                └── FOUND
                        │
                        ▼
                  map entity → TimetableView
                        │
                        ├── status = ACTIVE or EMERGENCY_ACTIVE
                        │       └──▶  TTL = 5 minutes
                        │
                        ├── status = DRAFT or PENDING_REVIEW
                        │       └──▶  TTL = 30 seconds
                        │
                        └── status = APPROVED / REJECTED /
                                      SUPERSEDED / CANCELLED
                                  └──▶  TTL = 10 minutes
                        │
                        ▼
                  TimetableCacheService.put(view, ttl)
                        │
                        ▼
                  return TimetableView
```

### 7.3 Paginated List Endpoint

```
  GET /api/v1/lines/{lineId}/timetables?page=0&size=20&status=ACTIVE
        │
        ▼
  TimetableQueryController.listTimetables
        │
        ├── size > 100?  →  cap size = 100
        │
        ▼
  TimetableQueryService.listByLine(lineId, status, page, size)
        │
        ▼
  TimetableReadModelRepository.query(lineId, status filter, pageable)
        │
        ▼
  PagedResponse.of(content, page, size, totalElements)
    totalPages = ceil(totalElements / size)
    last       = (page >= totalPages - 1)
        │
        ▼
  return PagedResponse<TimetableView>
```

---

## 8. Layer 6 — Distribution / Fan-Out: Distribution Service

Port: 8084 (app + WebSocket) · 8090 (actuator)
Key files: `services/distribution-service/src/main/java/com/railway/platform/distribution/`

### 8.1 Saga Coordination Flow

```
  ┌──────────────────────────────────┐
  │ Kafka: railway.schedule.computed │
  └──────────────────┬───────────────┘
                     │ ScheduleComputedEvent (Avro)
                     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  ScheduleComputedConsumer                                            │
  │  @KafkaListener  @Transactional(kafkaTransactionManager)            │
  │                                                                      │
  │  idempotency check on eventId                                        │
  │  ┌── DUPLICATE ──▶ ack offset, return                               │
  │  └── NEW ──▶ DistributionOrchestrator.distribute(event, correlId)   │
  └───────────────────────────────────────┬──────────────────────────────┘
                                          │
                                          ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  DistributionOrchestrator  (Saga Coordinator)                        │
  │                                                                      │
  │  Channel 1 — WebSocket  (lowest latency, fire first)                │
  │  ───────────────────────────────────────────────────                 │
  │  WebSocketDistributor                                                │
  │    SimpMessagingTemplate.convertAndSend(                             │
  │      "/topic/lines/{lineId}/schedule",                               │
  │      { timetableId, lineId, effectiveDate,                           │
  │        triggeringEventId, isEmergency })                             │
  │                                                                      │
  │    if isEmergency == true:                                           │
  │      SimpMessagingTemplate.convertAndSend(                           │
  │        "/topic/emergency",                                           │
  │        { broadcast to ALL subscribers })                             │
  │                                                                      │
  │  Channel 2 — Passenger App Push                                      │
  │  ────────────────────────────────                                    │
  │  PassengerAppDistributor                                             │
  │    publish NotificationRequestEvent to                               │
  │    railway.notification.requests                                     │
  │    { recipientIds, channels, template,                               │
  │      templateVars, isEmergency }                                     │
  │                                                                      │
  │  Channel 3 — Station Displays                                        │
  │  ────────────────────────────────                                    │
  │  StationDisplayDistributor.distribute(event)                         │
  │                                                                      │
  │  Channel 4 — Partner Feeds                                           │
  │  ────────────────────────────                                        │
  │  PartnerFeedDistributor.distribute(event)                            │
  │                                                                      │
  │  Saga compensation check:                                            │
  │    ALL non-WS channels failed?                                       │
  │      YES → publish DistributionEvent{status=COMPENSATED}            │
  │              metric: distribution.channel.failures.total             │
  │      NO  → publish DistributionEvent per channel                    │
  │              {status=PUBLISHED or FAILED}                            │
  │                                                                      │
  │  distribution_db.save(DistributionTrackingEntity)                    │
  │    ← per-channel success/failure record                              │
  └──────────────────────────────────────────────────────────────────────┘
        │
        │  ack Kafka offset
        ▼
  distributed to all channels
```

### 8.2 WebSocket / STOMP Configuration

```
  WebSocketConfig  (distribution-service)
  ─────────────────────────────────────────────────────────────
  STOMP endpoint:  /ws
  SockJS fallback: enabled
  In-memory SimpleBroker: /topic prefix
  Application destination prefix: /app
  User destination prefix: /user

  Auth: JWT passed as ?access_token= query param
        on /ws WebSocket handshake

  Subscription Topics
  ─────────────────────────────────────────────────────────────
  ┌──────────────────────────────────────────────────────────┐
  │  /topic/lines/{lineId}/schedule                          │
  │  Line-specific schedule updates                          │
  │  payload: { timetableId, lineId, effectiveDate,          │
  │             triggeringEventId, isEmergency }             │
  └──────────────────────────────────────────────────────────┘
  ┌──────────────────────────────────────────────────────────┐
  │  /topic/emergency                                        │
  │  Broadcast emergency to ALL subscribers                  │
  │  (not limited to any lineId)                             │
  └──────────────────────────────────────────────────────────┘

  Angular Operator Console  (frontend)
  ─────────────────────────────────────────────────────────────
  ┌──────────────────────────────────────────────────────────┐
  │  @stomp/stompjs Client                                   │
  │  reconnectDelay: 5000 ms                                 │
  │  auto-reconnect on disconnect                            │
  │                                                          │
  │  connected = signal<boolean>(false)  ← NgRx Signals      │
  │                                                          │
  │  NgRx Signals Store                                      │
  │    onScheduleUpdate() handler                            │
  │    → UI re-renders reactively                            │
  └──────────────────────────────────────────────────────────┘
```

---

## 9. Layer 7 — Notifications: Notification Service

Port: 8085 (app) · 8090 (actuator)
Key files: `services/notification-service/src/main/java/com/railway/platform/notification/`

```
  ┌────────────────────────────────────┐
  │  Kafka: railway.notification.reqs  │
  └────────────────────┬───────────────┘
                       │ NotificationRequestEvent (Avro)
                       ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  NotificationRequestConsumer                                         │
  │  @KafkaListener  @Transactional  (DB only)                          │
  │                                                                      │
  │  Step 1 — Idempotency check                                          │
  │    ProcessedEventRepository.existsByEventId(event.eventId)           │
  │    ┌── DUPLICATE ──▶ ack offset, return                             │
  │    └── NEW ──▶ continue                                              │
  │                                                                      │
  │  Step 2 — Check scheduleAfter timestamp                              │
  │    future timestamp → log WARN (Phase 5 scheduler handles)           │
  │                                                                      │
  │  Step 3 — Render template                                            │
  │    TemplateRenderer.render(template, templateVariables)              │
  │    returns { title, body }                                           │
  │                                                                      │
  │  Step 4 — Multi-channel delivery  (partial failure tolerated)        │
  │                                                                      │
  │    ┌─────────────────────────────────────────────────────────┐      │
  │    │  PushNotificationProvider  (Firebase FCM)               │      │
  │    │  send(recipientIds, title, body, isEmergency, correlId) │      │
  │    │  isEmergency=true → FCM priority=HIGH                   │      │
  │    │    → bypasses Android Doze mode                         │      │
  │    └─────────────────────────────────────────────────────────┘      │
  │    ┌─────────────────────────────────────────────────────────┐      │
  │    │  SmsNotificationProvider  (Twilio)                      │      │
  │    │  send(phoneNumbers, title, body, isEmergency, correlId) │      │
  │    └─────────────────────────────────────────────────────────┘      │
  │    ┌─────────────────────────────────────────────────────────┐      │
  │    │  EmailNotificationProvider  (SendGrid / SES)            │      │
  │    │  send(emailAddresses, title, body, isEmergency, correlId│      │
  │    └─────────────────────────────────────────────────────────┘      │
  │                                                                      │
  │    Failure in one channel does NOT stop the other channels.          │
  │                                                                      │
  │    Metrics emitted:                                                  │
  │      notification.deliveries.total { channel }                       │
  │      notification.delivery.errors.total { channel, reason }          │
  │                                                                      │
  │  Step 5 — Persist delivery record                                    │
  │    notification_db.INSERT INTO sent_notifications                    │
  │      (recipient, channel, status, correlation_id, sent_at)           │
  │      ← one row per recipient per channel                             │
  │                                                                      │
  │  Step 6 — Ack + cleanup                                              │
  │    ProcessedEventRepository.save({eventId})                          │
  │    ack Kafka offset                                                  │
  └──────────────────────────────────────────────────────────────────────┘
```

---

## 10. Layer 8 — Observability Stack

```
  ALL 6 SERVICES  (port :8090/actuator)
  ─────────────────────────────────────────────────────────────────────────
  ┌──────────────────────────────────────────────────────────────────────┐
  │  Metrics   /actuator/prometheus                                      │
  │  Micrometer Prometheus registry                                      │
  └────────────────────────────┬─────────────────────────────────────────┘
                               │ scrape every 15s
                               ▼
                      ┌─────────────────┐
                      │   Prometheus    │
                      │  15-day retent. │
                      └────────┬────────┘
                               │
                               ▼
                      ┌─────────────────┐
                      │   Grafana 12.x  │
                      │                 │
                      │  Dashboards:    │
                      │  platform-      │
                      │  overview       │
                      │                 │
                      │  kafka-consumer │
                      │  -lag           │
                      │                 │
                      │  timetable-     │
                      │  service        │
                      │                 │
                      │  jvm-overview   │
                      └─────────────────┘

  ┌──────────────────────────────────────────────────────────────────────┐
  │  Traces    OTel spans                                                │
  │  micrometer-tracing-bridge-otel                                      │
  │  opentelemetry-exporter-otlp                                         │
  └────────────────────────────┬─────────────────────────────────────────┘
                               │ OTLP gRPC :4317 / HTTP :4318
                               ▼
                  ┌──────────────────────────┐
                  │   Grafana Alloy           │
                  │   (OTel Collector)        │
                  │   OTLP receiver           │
                  └────────────┬─────────────┘
                               │
                               ▼
                      ┌────────────────┐
                      │  Grafana Tempo │
                      │  Distributed   │
                      │  traces        │
                      │  W3C tracep.   │
                      └────────┬───────┘
                               │
                               ▼
                      ┌─────────────────┐
                      │   Grafana 12.x  │
                      └─────────────────┘

  ┌──────────────────────────────────────────────────────────────────────┐
  │  Logs      logback-spring.xml                                        │
  │  k8s profile     → ECS JSON structured logs                          │
  │  other profiles  → plain-text                                        │
  └────────────────────────────┬─────────────────────────────────────────┘
                               │ pod stdout / log tail
                               ▼
                  ┌──────────────────────────┐
                  │   Grafana Alloy           │
                  │   Log tail / file scrape  │
                  └────────────┬─────────────┘
                               │
                               ▼
                      ┌────────────────┐
                      │  Grafana Loki  │
                      │  Log aggreg.   │
                      │  labels:       │
                      │  service,      │
                      │  environment,  │
                      │  pod           │
                      └────────┬───────┘
                               │
                               ▼
                      ┌─────────────────┐
                      │   Grafana 12.x  │
                      └─────────────────┘

  ┌──────────────────────────────────────────────────────────────────────┐
  │  Health    /actuator/health                                          │
  │  liveness  · readiness · kafka · outboxLag                          │
  └────────────────────────────┬─────────────────────────────────────────┘
                               │ probed by Kubernetes
                               ▼
                  ┌──────────────────────────────────┐
                  │  Kubernetes Health Probes         │
                  │                                  │
                  │  Liveness:                       │
                  │  GET :8090/actuator/health/       │
                  │      liveness                    │
                  │  → failure → pod restart         │
                  │                                  │
                  │  Readiness:                      │
                  │  GET :8090/actuator/health/       │
                  │      readiness                   │
                  │  → failure → remove from LB      │
                  └──────────────────────────────────┘

  CORRELATION ID PROPAGATION CHAIN (end-to-end)
  ─────────────────────────────────────────────────────────────────────────
  HTTP X-Correlation-Id header
    → MDC correlationId field
    → structured log field (correlationId)
    → Kafka record header  (correlation_id)
    → OTel W3C traceparent

  Links every log line and trace span across all 6 services end-to-end.
```

---

## 11. Layer 9 — Infrastructure: Helm + Terraform + CI/CD

### 11.1 Helm Chart Structure (per service)

```
  infra/helm/{service}/
  ├── values.yaml            ← default values
  ├── values-prod.yaml       ← production overrides:
  │                             memory requests/limits
  │                             replica counts
  │                             HPA CPU target
  │                             PDB minAvailable
  └── templates/
      ├── deployment.yaml    ← Vault Agent sidecar annotations
      │                         readOnlyRootFilesystem=true
      │                         allowPrivilegeEscalation=false
      │                         capabilities.drop=[ALL]
      ├── service.yaml       ← ClusterIP
      │                         app port 808x + actuator 8090
      ├── hpa.yaml           ← HorizontalPodAutoscaler
      │                         CPU target 70%
      │                         min/max replicas from values
      ├── pdb.yaml           ← PodDisruptionBudget
      │                         minAvailable from values
      ├── networkpolicy.yaml ← deny-all default ingress
      │                         egress allow: DNS, Postgres,
      │                           Kafka, Redis, Vault, OTel
      │                         api-gateway ingress: allow all
      ├── configmap.yaml     ← SPRING_PROFILES_ACTIVE=k8s
      │                         JAVA_OPTS
      │                         SPRING_CONFIG_IMPORT
      └── ingress.yaml       ← ALB annotations + ACM cert ARN
                                (api-gateway only)

  VAULT AGENT SIDECAR INJECTOR
  ─────────────────────────────────────────────────────────────────────────
  deployment.yaml annotations:
    vault.hashicorp.com/agent-inject: "true"
    vault.hashicorp.com/role: "{service}"
        │
        ▼
  Vault Agent writes secrets to
  /vault/secrets/application.properties
  (in-memory emptyDir volume)
        │
        ▼
  Spring Boot loads secrets via
  SPRING_CONFIG_IMPORT env var
  (spring.cloud.vault.enabled=false on k8s profile)
```

### 11.2 Terraform Module Dependency Graph

```
  infra/terraform/modules/
                                   ┌──────────────────────────────┐
                                   │           vpc                │
                                   │  Subnets (public/private)    │
                                   │  NAT Gateway                 │
                                   │  Security Groups             │
                                   │  Route Tables                │
                                   └────────────┬─────────────────┘
                                                │ networking
                    ┌───────────────────────────┼─────────────────────────┐
                    │                           │                         │
                    ▼                           ▼                         ▼
  ┌─────────────────────────┐  ┌────────────────────────┐  ┌─────────────────────┐
  │          eks            │  │          rds            │  │         msk         │
  │  EKS 1.31 cluster       │  │  PostgreSQL 16 multi-AZ │  │  Kafka 3.7          │
  │  System + App nodegroups│  │  wal_level=logical      │  │  SASL/SCRAM         │
  │  OIDC provider (IRSA)   │  │  encrypted              │  │  KRaft (no ZK)      │
  └──────────┬──────────────┘  │  deletion_protection    │  │  Schema Registry    │
             │                 │  password → Secrets Mgr │  └─────────────────────┘
             │                 └────────────────────────┘
             │
             ▼
  ┌─────────────────────────┐  ┌────────────────────────┐
  │          ecr            │  │      elasticache        │
  │  One repository per svc │  │  Redis cluster          │
  │  image scanning enabled │  │  auth + encryption      │
  │  lifecycle: last 30 img │  │  in transit             │
  └─────────────────────────┘  └────────────────────────┘

  NOTE: Bootstrap required before terraform init:
    S3 bucket      → Terraform remote state
    DynamoDB table → Terraform state lock
    See docs/CONFIGURATION.md
```

### 11.3 CI/CD Pipeline Flow

```
  ci-backend.yml
  ─────────────────────────────────────────────────────────────────────────
  Trigger: push to services/**, shared/**, or pom.xml

  Maven matrix build — 6 services in parallel (fail-fast: false)
    ├── mvn test
    │     unit tests (no Docker required)
    └── mvn verify -Dgroups=integration
          Testcontainers: PostgreSQL + Kafka + Redis

  Branch = main?
    YES → docker build
          docker push :{git-sha} to ECR

  ─────────────────────────────────────────────────────────────────────────
  ci-frontend.yml
  ─────────────────────────────────────────────────────────────────────────
  Trigger: push to frontend/**

  ├── ng build --configuration=production
  ├── ng test --browsers=ChromeHeadless  (Karma + Jasmine + coverage)
  └── ESLint (flat config, Angular 21)

  ─────────────────────────────────────────────────────────────────────────
  security-scan.yml
  ─────────────────────────────────────────────────────────────────────────
  Trigger: weekly schedule + every PR

  ├── OWASP Dependency Check  (NVD API key — avoids rate limit)
  ├── Trivy image scanning     (CRITICAL/HIGH → fail build)
  └── TruffleHog secret scanning

  ─────────────────────────────────────────────────────────────────────────
  cd-deploy.yml
  ─────────────────────────────────────────────────────────────────────────
  Trigger:
    dev  → automatic on every push to main
    prod → manual workflow dispatch
             requires GitHub Environment approval

  helm upgrade --install \
    --atomic --wait --timeout 5m \
    (sequential per service)

  K8s health check: GET :8090/actuator/health

  Slack webhook: deploy notification on completion

  ─────────────────────────────────────────────────────────────────────────
  dependabot.yml
  ─────────────────────────────────────────────────────────────────────────
  Trigger: weekly (Monday)

  ├── Maven updates  (ignore Spring Boot/Cloud major versions)
  ├── npm updates    (ignore Angular/NgRx major versions)
  └── GitHub Actions updates
```

---

## 12. End-to-End Flow: Timetable Change to Passenger Notification

This trace follows a single timetable approval from operator click through to push
notification. Annotated with latency budgets targeting the ≤5 s (p95) platform SLA.

```
  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 1 — HTTP Write Path  (~50 ms)                                 ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Operator Browser (Angular 21)
        │
        │  POST /api/v1/timetables/{id}/approve
        │  Authorization: Bearer {JWT ROLE_TIMETABLE_APPROVER}
        ▼
  ┌─────────────────┐
  │  API Gateway    │  JWT validate · RBAC check · rate limit · circuit breaker
  │  :8080          │
  └────────┬────────┘
           │  POST .../approve   X-Correlation-Id: {uuid}
           ▼
  ┌─────────────────┐
  │ Timetable Svc   │  UPDATE timetables (version++)
  │ :8081           │  INSERT outbox_events {status=APPROVED}
  │                 │  INSERT audit_log
  │                 │  ── single ACID COMMIT ──
  └────────┬────────┘
           │  200 OK { timetableId, status: APPROVED }
           ▼
  API Gateway  →  200 OK  →  Operator Browser

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 2 — Debezium CDC  (~100 ms)                                   ║
  ╚══════════════════════════════════════════════════════════════════════╝

  timetable_db WAL
        │  outbox_events INSERT
        ▼
  ┌─────────────────┐
  │  Debezium CDC   │
  └────────┬────────┘
           │  TimetableChangedEvent (changeType=APPROVED)
           │  key=timetableId  ·  header: correlation_id
           ▼
  ┌─────────────────────────────┐
  │  Kafka: railway.timetable   │
  │         .changed            │
  └─────────────────────────────┘

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 3 — Query Projection + Cache Evict  (~200 ms)                 ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Kafka: railway.timetable.changed
        │
        ▼
  ┌─────────────────┐
  │  Query Service  │  idempotency check
  │  :8083          │  UPSERT TimetableReadModelEntity(status=APPROVED)
  │                 │  evict Redis cache(timetableId)
  │                 │  ack offset
  └─────────────────┘

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 4 — Schedule Computation  (~300 ms)                           ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Kafka: railway.timetable.changed
        │
        ▼
  ┌─────────────────┐
  │ Schedule Svc    │  idempotency check
  │ :8082           │  computeServices(event)
  │                 │  publish ScheduleComputedEvent  (Kafka tx)
  │                 │  ack offset
  └─────────────────┘
        │
        ▼
  Kafka: railway.schedule.computed

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 5 — Distribution Fan-Out  (~500 ms, WebSocket first)          ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Kafka: railway.schedule.computed
        │
        ▼
  ┌─────────────────────┐
  │ Distribution Svc    │  idempotency check
  │ :8084               │
  └──────────┬──────────┘
             │
             ├──▶ STOMP /topic/lines/{lineId}/schedule
             │      { timetableId, lineId, effectiveDate, isEmergency=false }
             │                │
             │                ▼
             │      Operator Browser  (~50 ms sub-path)
             │      NgRx Signals Store.onScheduleUpdate()
             │      UI re-renders with new timetable status
             │
             └──▶ NotificationRequestEvent
                    → Kafka: railway.notification.requests
                    { recipientIds, channels=[PUSH,SMS,EMAIL],
                      template=TIMETABLE_APPROVED }

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 6 — Notification Dispatch  (~1–2 s)                           ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Kafka: railway.notification.requests
        │
        ▼
  ┌─────────────────────┐
  │ Notification Svc    │  idempotency check
  │ :8085               │  TemplateRenderer.render(template, vars)
  └──────────┬──────────┘
             │
             ├──▶ Firebase FCM  →  passenger push notifications
             ├──▶ Twilio SMS    →  passenger SMS
             └──▶ SendGrid/SES  →  passenger email
             │
             ▼
  record SentNotificationEntity per recipient
  ack Kafka offset

  ┌──────────────────────────────────────────────────────────────────────┐
  │  Total latency: ≤5 s p95  (platform SLA)                            │
  │  Correlation ID threads all spans and logs across every hop.         │
  └──────────────────────────────────────────────────────────────────────┘
```

---

## 13. End-to-End Flow: Emergency Activation

Emergency activation bypasses the normal approval workflow entirely and triggers
elevated-priority delivery across all channels simultaneously.

```
  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 1 — Emergency Command  (highest-priority path)                ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Emergency Operator  (ROLE_EMERGENCY_OPERATOR)
        │
        │  POST /api/v1/timetables/{id}/emergency-activate
        │  { "justification": "track 3 incident — signal failure" }
        ▼
  ┌─────────────────┐
  │  API Gateway    │  RBAC: ONLY ROLE_EMERGENCY_OPERATOR passes
  │  :8080          │  any other role → 403 Forbidden immediately
  │                 │  dedicated emergency circuit breaker route
  └────────┬────────┘
           │
           ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │  Timetable Service :8081                                            │
  │                                                                     │
  │  Timetable.activateEmergency(actor, justification)                  │
  │  ApprovalStateMachine allows:                                       │
  │    DRAFT          → EMERGENCY_ACTIVE                                │
  │    PENDING_REVIEW → EMERGENCY_ACTIVE                                │
  │  Invariant: justification must not be blank                         │
  │                                                                     │
  │  UPDATE timetables SET status=EMERGENCY_ACTIVE                      │
  │  INSERT outbox_events { changeType=EMERGENCY_ACTIVATED }            │
  │  INSERT audit_log { justification="track 3 incident..." }           │
  │  ── single ACID COMMIT ──                                           │
  └────────┬────────────────────────────────────────────────────────────┘
           │  200 OK
           ▼
  API Gateway  →  200 OK  →  Emergency Operator

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 2 — CDC + Kafka Propagation                                   ║
  ╚══════════════════════════════════════════════════════════════════════╝

  timetable_db WAL
        │
        ▼
  Debezium CDC
        │  TimetableChangedEvent (changeType=EMERGENCY_ACTIVATED)
        ▼
  Kafka: railway.timetable.changed

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 3 — Schedule Computation  (isEmergency propagated)            ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Kafka: railway.timetable.changed
        │
        ▼
  Schedule Service :8082
        │  isEmergency=true propagated into ScheduleComputedEvent
        ▼
  Kafka: railway.schedule.computed { isEmergency=true }

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 4 — Emergency Distribution Fan-Out                            ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Kafka: railway.schedule.computed
        │
        ▼
  ┌─────────────────────┐
  │ Distribution Svc    │
  │ :8084               │
  └──────────┬──────────┘
             │
             ├──▶ STOMP /topic/lines/{lineId}/schedule
             │      { isEmergency=true }
             │
             ├──▶ STOMP /topic/emergency
             │      broadcast to ALL subscribers
             │      (not limited to lineId)
             │      ALL operator consoles notified simultaneously
             │
             └──▶ NotificationRequestEvent { isEmergency=true }
                    → Kafka: railway.notification.requests

  ╔══════════════════════════════════════════════════════════════════════╗
  ║  Step 5 — Elevated-Priority Notification Delivery                   ║
  ╚══════════════════════════════════════════════════════════════════════╝

  Kafka: railway.notification.requests
        │
        ▼
  Notification Service :8085
        │
        ├──▶ Firebase FCM
        │      priority=HIGH → bypasses Android Doze mode
        │      immediate delivery
        │
        ├──▶ Twilio SMS
        │      immediate send
        │      elevated rate limits
        │
        └──▶ SendGrid / SES Email
               standard delivery
               (isEmergency=true elevates provider priority)

  isEmergency=true elevates provider priority and rate limits
  across all three channels simultaneously.
```

---

## 14. Appendix: Idempotency Pattern (All Consumers)

Every Kafka consumer in the platform follows this identical recipe,
ensuring safe at-least-once delivery.

Applies to: Schedule Service, Query Service, Distribution Service,
            Notification Service.

```
  ┌──────────────────────────────────────────────────────────────────────┐
  │  KAFKA MESSAGE ARRIVES                                               │
  │  (TimetableChangedEvent / ScheduleComputedEvent /                    │
  │   NotificationRequestEvent)                                          │
  └────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  STEP 1 — Propagate Correlation ID                                   │
  │  KafkaCorrelationIdPropagator                                        │
  │  extract correlationId from Kafka record headers                     │
  │  MDC.put("correlationId", id)                                        │
  └────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  STEP 2 — Idempotency Check                                          │
  │  SELECT FROM processed_events WHERE event_id = ?                     │
  └────────────────────┬──────────────────────┬──────────────────────────┘
                       │                      │
              FOUND (duplicate)          NOT FOUND (new)
                       │                      │
                       ▼                      ▼
              log WARN "duplicate       ┌────────────────────────────────┐
              eventId — skipping"       │  STEP 3 — Business Logic       │
              ack Kafka offset          │  projection / computation /    │
              MDC.clear()              │  distribution / notification   │
              (done)                   └────────────────┬───────────────┘
                                                        │
                                                        ▼
                                       ┌────────────────────────────────┐
                                       │  STEP 4 — Mark Processed       │
                                       │  INSERT INTO processed_events  │
                                       │  (event_id, topic,             │
                                       │   processed_at)                │
                                       │  UNIQUE constraint on event_id │
                                       └────────────────┬───────────────┘
                                                        │
                                       DataIntegrityViolationException?
                                       (race between consumer instances)
                                                        │
                                       ┌────────────────┴────────────────┐
                                       │                                 │
                                      YES                               NO
                                       │                                 │
                                       ▼                                 ▼
                              treat as duplicate             ack Kafka offset
                              ack Kafka offset               MDC.clear()
                              (safe to skip)                      │
                              MDC.clear()                         ▼
                              (done)                   Exception thrown?
                                                                  │
                                                   ┌──────────────┴──────────────┐
                                                   │                             │
                                          Non-retryable                     Transient
                                          exception                         exception
                                                   │                             │
                                                   ▼                             ▼
                                          DefaultErrorHandler           Retry 3× exponential
                                          publish to                    backoff:
                                          railway.dlq                   100 ms → 200 ms → 400 ms
                                          (original-topic                         │
                                           header preserved)            Still failing?
                                          (done)                              │
                                                                              ▼
                                                                        → railway.dlq
                                                                        (done)

  ┌──────────────────────────────────────────────────────────────────────┐
  │  processed_events table  (present in every consumer service's DB)    │
  ├──────────────────────────────────────────────────────────────────────┤
  │  event_id       VARCHAR   UNIQUE constraint  ← prevents duplicates   │
  │  topic          VARCHAR                                              │
  │  processed_at   TIMESTAMP                                            │
  ├──────────────────────────────────────────────────────────────────────┤
  │  ProcessedEventsCleanupJob (@Scheduled)                              │
  │  deletes rows older than retention window                            │
  │  (see infrastructure/maintenance/ in each service)                   │
  └──────────────────────────────────────────────────────────────────────┘
```

---

*All class names, file paths, topic names, and configuration values reflect the actual implementation in this repository.*
