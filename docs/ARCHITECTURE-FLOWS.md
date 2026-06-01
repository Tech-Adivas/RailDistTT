# Railway Timetable Distribution Platform — Technical Architecture Flows

> Every layer documented below maps directly to code in this repository.
> File paths are relative to the repo root.

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

---

## 1. System Overview

```mermaid
graph TB
    subgraph Frontend["Frontend (Angular 21)"]
        OC[Operator Console<br/>NgRx Signals Store<br/>STOMP WebSocket client]
    end

    subgraph Edge["Edge Layer"]
        GW[API Gateway :8080<br/>Spring Cloud Gateway<br/>OIDC · RBAC · Rate Limit · Circuit Breaker]
    end

    subgraph WriteLayer["Write Layer"]
        TS[Timetable Service :8081<br/>DDD Aggregate<br/>Transactional Outbox]
    end

    subgraph EventBus["Event Bus"]
        DB[(PostgreSQL<br/>outbox_events)]
        DEZ[Debezium CDC<br/>pgoutput → Kafka]
        KF[[Kafka / MSK<br/>5 topics]]
        SR[Schema Registry<br/>Avro BACKWARD compat]
    end

    subgraph ComputeLayer["Compute Layer"]
        SS[Schedule Service :8082<br/>Kafka consumer<br/>Transactional publish]
    end

    subgraph ReadLayer["Read Layer"]
        QS[Query Service :8083<br/>CQRS projector<br/>Redis cache · REST API]
        REDIS[(Redis / ElastiCache)]
    end

    subgraph DistLayer["Distribution Layer"]
        DS[Distribution Service :8084<br/>Fan-out Saga<br/>WebSocket/STOMP]
    end

    subgraph NotifLayer["Notification Layer"]
        NS[Notification Service :8085<br/>FCM · SMS · Email]
    end

    subgraph Observe["Observability"]
        PROM[Prometheus]
        GRAF[Grafana]
        LOKI[Loki]
        TEMPO[Tempo]
        ALLOY[Grafana Alloy<br/>OTel collector]
    end

    subgraph SharedLib["Shared Libraries"]
        CL[common-lib<br/>CorrelationId · ExceptionHandler · KafkaHealth]
        EV[events<br/>Avro schemas · Topics constants]
    end

    OC -->|HTTPS /api| GW
    OC <-->|WSS /ws STOMP| DS
    GW -->|GET| QS
    GW -->|POST/PATCH| TS
    GW -->|WS upgrade| DS
    TS -->|same tx| DB
    DB -->|CDC WAL| DEZ
    DEZ -->|Avro| KF
    KF <--> SR
    KF -->|TimetableChangedEvent| SS
    KF -->|TimetableChangedEvent| QS
    SS -->|ScheduleComputedEvent| KF
    KF -->|ScheduleComputedEvent| DS
    DS -->|NotificationRequestEvent| KF
    KF -->|NotificationRequestEvent| NS
    QS <--> REDIS

    TS & SS & QS & DS & NS & GW -->|traces OTLP| ALLOY
    ALLOY --> TEMPO
    ALLOY --> LOKI
    PROM -->|scrape :8090| TS & SS & QS & DS & NS & GW
    PROM --> GRAF
    TEMPO --> GRAF
    LOKI --> GRAF
```

---

## 2. Layer 0 — Shared Foundation

These modules are compiled into every service. They provide the cross-cutting skeleton that the rest of the platform depends on.

### 2.1 Shared Events (`shared/events/`)

```mermaid
graph LR
    subgraph Avro["Avro Schemas (src/main/avro/)"]
        CM[common.avsc<br/>EventMetadata<br/>eventId · eventType<br/>occurredAt · correlationId<br/>actor · schemaVersion]
        TC[timetable-changed-event.avsc<br/>TimetableChangedEvent<br/>changeType enum<br/>timetableId · lineId<br/>effectiveDate · expiryDate]
        SC[schedule-computed-event.avsc<br/>ScheduleComputedEvent<br/>timetableId · lineId<br/>services list<br/>triggeringEventId]
        DE[distribution-event.avsc<br/>DistributionEvent<br/>channel · status<br/>failureReason]
        NR[notification-request-event.avsc<br/>NotificationRequestEvent<br/>recipientIds · channels<br/>template variables<br/>isEmergency]
        MW[maintenance-window-event.avsc<br/>MaintenanceWindowEvent]
    end

    subgraph Topics["Topics.java constants"]
        T1["railway.timetable.changed"]
        T2["railway.schedule.computed"]
        T3["railway.distribution.events"]
        T4["railway.notification.requests"]
        T5["railway.maintenance.windows"]
        T6["railway.dlq"]
    end

    TC --> T1
    SC --> T2
    DE --> T3
    NR --> T4
    MW --> T5
```

**changeType enum values** on `TimetableChangedEvent`:
`CREATED · UPDATED · SUBMITTED_FOR_REVIEW · APPROVED · REJECTED · ACTIVATED · SUPERSEDED · CANCELLED · EMERGENCY_ACTIVATED · MAINTENANCE_WINDOW_APPLIED`

### 2.2 Common Library (`shared/common-lib/`)

```mermaid
flowchart TD
    REQ[Inbound HTTP Request] --> CIF

    subgraph CIF["CorrelationIdFilter (HIGHEST_PRECEDENCE)"]
        A{X-Correlation-Id<br/>header present?}
        A -->|yes| B[use existing UUID]
        A -->|no| C[mint new UUID v4]
        B & C --> D[MDC.put correlationId]
        D --> E[CorrelationIdHolder.set]
        E --> F[continue filter chain]
        F --> G[echo X-Correlation-Id in response]
        G --> H[MDC.clear in finally]
    end

    KAFKA[Inbound Kafka Record] --> KP

    subgraph KP["KafkaCorrelationIdPropagator"]
        K1[extract correlationId<br/>from record headers]
        K1 --> K2[MDC.put correlationId]
        K2 --> K3[CorrelationIdHolder.set]
        K3 --> K4[consumer logic runs]
        K4 --> K5[MDC.clear in finally]
    end

    subgraph GEH["GlobalExceptionHandler (@RestControllerAdvice)"]
        EX1[DomainException] -->|422| R1["ApiError{code, message, correlationId}"]
        EX2[MethodArgumentNotValidException] -->|422| R2["ApiError{fieldErrors[]}"]
        EX3[ConstraintViolationException] -->|422| R3[ApiError per constraint]
        EX4[Exception catch-all] -->|500| R4[ApiError — no stack trace in body]
    end

    subgraph KHI["KafkaHealthIndicator"]
        KH1[AdminClient.describeCluster] -->|&lt;3s timeout| KH2{reachable?}
        KH2 -->|yes| UP[Health.up]
        KH2 -->|no| DOWN[Health.down]
    end
```

---

## 3. Layer 1 — Edge: API Gateway

**Port:** 8080 (app) · 8090 (actuator)
**Key files:** `services/api-gateway/src/main/java/com/railway/platform/gateway/config/`

### 3.1 Request Processing Pipeline

```mermaid
flowchart TD
    CLIENT[Browser / Mobile App] -->|HTTPS| LB[AWS ALB\nACM TLS termination]
    LB -->|HTTP| GW

    subgraph GW["API Gateway (Spring Cloud Gateway — WebFlux)"]
        direction TB
        SEC[SecurityWebFilterChain\nJWT decoder\nNimbusReactiveJwtDecoder] --> RBAC

        subgraph RBAC["Role-Based Authorization"]
            R1["EMERGENCY_OPERATOR\n→ POST /*/emergency-activate"]
            R2["TIMETABLE_APPROVER or ADMIN\n→ POST /*/approve /reject /request-changes"]
            R3["TIMETABLE_AUTHOR or ADMIN\n→ POST create · submit · cancel\n   PATCH update"]
            R4["Authenticated\n→ all other /api/** + /ws/**"]
            R5["Public\n→ /actuator/health /actuator/info /fallback/**"]
        end

        RBAC --> ROUTER

        subgraph ROUTER["GatewayRoutesConfig — CQRS Routing"]
            WS["WS /ws/** → distribution-service:8084\n(circuit breaker only, no rate limit)"]
            EMG["POST /*/emergency-activate → timetable-service:8081\n(emergency-activate circuit breaker)"]
            WRT["POST/PATCH /api/v1/timetables/** → timetable-service:8081\n(timetable-write circuit breaker)"]
            RD["GET /api/v1/** → query-service:8083\n(query-read circuit breaker)"]
        end

        ROUTER --> RL

        subgraph RL["RedisRateLimiter (RateLimitConfig)"]
            RLK{JWT present?}
            RLK -->|yes| RLU["key = user:{JWT.sub}\nper-user token bucket"]
            RLK -->|no| RLI["key = ip:{X-Forwarded-For}\nper-IP token bucket"]
            RLU & RLI -->|default 100 req/s, burst 200| RLC{tokens available?}
            RLC -->|yes| PASS[consume 1 token → forward]
            RLC -->|no| R429[429 Too Many Requests]
        end

        RL --> CB

        subgraph CB["Resilience4j Circuit Breaker"]
            CBS[sliding window 10 calls\n50% failure threshold\n30s wait in OPEN] --> CBT{state?}
            CBT -->|CLOSED| FWD[forward to upstream]
            CBT -->|OPEN| F503[503 → /fallback/{service}]
            CBT -->|HALF_OPEN| PROBE[allow 3 probe calls]
        end
    end

    CB --> TS[timetable-service]
    CB --> QS[query-service]
    CB --> DS[distribution-service]

    subgraph HDRS["Headers Added to Upstream"]
        H1["X-Correlation-Id: {uuid}"]
        H2["Authorization: Bearer {JWT}  (passed through)"]
        H3["X-Forwarded-For, X-Forwarded-Host"]
    end
```

### 3.2 JWT Authority Mapping

```mermaid
flowchart LR
    JWT[JWT token\nroles claim] --> CONV[JwtAuthenticationConverter\nJwtGrantedAuthoritiesConverter]
    CONV -->|prefix ROLE_| AUTH["Spring Security Authorities\nROLE_TIMETABLE_AUTHOR\nROLE_TIMETABLE_APPROVER\nROLE_EMERGENCY_OPERATOR\nROLE_ADMIN"]
    AUTH --> AUTHZ[SpEL @PreAuthorize / SecurityWebFilterChain rules]
```

---

## 4. Layer 2 — Write Side: Timetable Service

**Port:** 8081 (app) · 8090 (actuator)
**Key files:** `services/timetable-service/src/main/java/com/railway/platform/timetable/`

### 4.1 Domain Model — Approval State Machine

```mermaid
stateDiagram-v2
    [*] --> DRAFT : create()\nROLE_TIMETABLE_AUTHOR

    DRAFT --> PENDING_REVIEW : submitForReview()
    DRAFT --> CANCELLED : cancel()

    PENDING_REVIEW --> APPROVED : approve(reviewerId)\nROLE_TIMETABLE_APPROVER\n[reviewer ≠ author]
    PENDING_REVIEW --> REJECTED : reject(reviewerId, reason)
    PENDING_REVIEW --> DRAFT : requestChanges(reviewerId)
    PENDING_REVIEW --> CANCELLED : cancel()

    APPROVED --> ACTIVE : activate()\nTimetableActivationScheduler\non effectiveDate
    APPROVED --> CANCELLED : cancel()

    ACTIVE --> SUPERSEDED : supersede()\nwhen newer timetable activates

    DRAFT --> EMERGENCY_ACTIVE : activateEmergency(actor, justification)\nROLE_EMERGENCY_OPERATOR only
    PENDING_REVIEW --> EMERGENCY_ACTIVE : activateEmergency()

    EMERGENCY_ACTIVE --> SUPERSEDED : supersede()

    REJECTED --> [*]
    SUPERSEDED --> [*]
    CANCELLED --> [*]
```

**Invariants enforced in `Timetable.java`:**
- No self-approval: `reviewer ≠ author` checked before `approve()`
- `activateEmergency()` requires explicit `justification` string (written to audit log)
- `ApprovalStateMachine.validateTransition()` guards every state change — throws `InvalidStateTransitionException` on illegal moves

### 4.2 Command Handler Transaction Flow

```mermaid
sequenceDiagram
    participant CTL as TimetableController
    participant CH as TimetableCommandHandler
    participant AGG as Timetable (Aggregate)
    participant SM as ApprovalStateMachine
    participant REPO as TimetableRepository (JPA)
    participant OEW as OutboxEventWriter
    participant ALW as AuditLogWriter
    participant DB as PostgreSQL

    CTL->>CH: handleCommand(cmd, actor)
    note over CH: @Transactional begins\n@Retryable(3× backoff 100–1000ms)

    CH->>REPO: findById(timetableId)\n(or Timetable.create() for new)
    REPO->>DB: SELECT with @Version lock
    DB-->>REPO: TimetableJpaEntity
    REPO-->>CH: Timetable aggregate

    CH->>AGG: domainMethod(params)
    AGG->>SM: validateTransition(currentStatus, targetStatus)
    SM-->>AGG: allowed / throws InvalidStateTransitionException

    AGG->>AGG: apply state change\naccumulate DomainEvent in memory

    CH->>REPO: save(aggregate)
    REPO->>DB: UPDATE timetables SET ... WHERE id=? AND version=?\n(optimistic lock — throws if version mismatch)

    CH->>OEW: write(domainEvents)
    OEW->>DB: INSERT INTO outbox_events\n(id, aggregate_type, aggregate_id,\nevent_type, payload, correlation_id)
    note over DB: Same transaction — atomic commit

    CH->>ALW: write(domainEvents, actor)
    ALW->>DB: INSERT INTO audit_log\n(before_status, after_status,\njustification, actor, created_at)

    note over DB: COMMIT — all three tables written atomically

    CH->>AGG: clearDomainEvents()
    CH-->>CTL: CommandResult / void
```

**`@Timed` metrics emitted on each command:**
`timetable.command.duration{command="approve"}`, `timetable.approvals.total`, `timetable.emergency_activations.total`

### 4.3 Transactional Outbox + Fallback Relay

```mermaid
flowchart TD
    subgraph TX["Single ACID Transaction"]
        AGG[Aggregate state saved] --> OE[outbox_events row INSERT\nrelay_published = false]
        OE --> AL[audit_log row INSERT]
    end

    subgraph CDC["Happy Path — Debezium CDC"]
        DEZ[Debezium monitors\npublic.outbox_events via\nPostgreSQL WAL / pgoutput]
        DEZ -->|row INSERT detected| ROUTE[OutboxEventRouter transform\naggregate_type=timetable\n→ topic: railway.timetable.changed]
        ROUTE --> KF[Kafka record published\nkey=aggregate_id\nheaders: correlation_id, event_type, created_at]
    end

    subgraph RELAY["Fallback Path — OutboxFallbackRelay"]
        SCHED["@Scheduled every 5 min"] --> QRY["SELECT * FROM outbox_events\nWHERE relay_published = false\nAND created_at < NOW() - 5min\n(lag threshold)"]
        QRY --> PUB[relayKafkaTemplate.send\nnon-transactional]
        PUB --> UPD["UPDATE outbox_events\nSET relay_published = true"]
    end

    subgraph HEALTH["OutboxLagHealthIndicator"]
        LAG["SELECT COUNT(*) FROM outbox_events\nWHERE relay_published = false\nAND age > lag_threshold_minutes"] --> STATUS{count > 0?}
        STATUS -->|yes| DN[Health DOWN\nK8s readiness fails]
        STATUS -->|no| UP[Health UP]
    end

    OE --> CDC
    OE --> RELAY
    OE --> HEALTH
```

### 4.4 Database Schema (timetable-service)

```mermaid
erDiagram
    timetables {
        uuid id PK
        varchar line_id
        varchar status "CHECK constraint"
        varchar name
        text description
        date effective_date
        date expiry_date
        varchar author_id
        varchar reviewer_id
        int version "optimistic lock"
        timestamp created_at
        timestamp updated_at
    }

    outbox_events {
        uuid id PK
        varchar aggregate_type "= 'timetable'"
        varchar aggregate_id FK
        varchar event_type
        text payload "JSON"
        varchar correlation_id
        boolean relay_published
        timestamp created_at
    }

    audit_log {
        uuid id PK
        varchar aggregate_type
        varchar aggregate_id FK
        varchar event_type
        text payload
        varchar actor
        varchar before_status
        varchar after_status
        text justification
        timestamp created_at
    }

    timetables ||--o{ outbox_events : "aggregate_id"
    timetables ||--o{ audit_log : "aggregate_id"
```

---

## 5. Layer 3 — CDC Transport: Debezium → Kafka

**Config:** `infra/debezium/application.properties`

```mermaid
flowchart LR
    subgraph PG["PostgreSQL (RDS / local)"]
        WAL[WAL replication slot\nplugin: pgoutput\nwal_level: logical]
        OE_TBL[public.outbox_events\n table]
    end

    subgraph DEZ["Debezium Server 3.1.2"]
        SRC[PostgresConnector\nsource connector]
        XFM[OutboxEventRouter transform\nrouting field: aggregate_type\ntopic: railway.$-{routedByValue}]
        HDRMAPPER[Header mapper\ncorrelation_id → Kafka header\nevent_type → Kafka header\ncreated_at → Kafka header]
    end

    subgraph KF["Kafka / MSK (KRaft, 3.7)"]
        T1[railway.timetable.changed]
        T2[railway.schedule.computed]
        T3[railway.distribution.events]
        T4[railway.notification.requests]
        T5[railway.maintenance.windows]
        DLQ[railway.dlq]
    end

    subgraph SR["Schema Registry (Confluent)"]
        AVRO[Avro schemas\nBACKWARD compat enforced\nby CI plugin]
    end

    subgraph OS["Offset Storage (Kafka topics)"]
        OFF[_debezium_offsets\nflush every 10s]
        HIST[_debezium_schema_history\nDDL snapshots]
    end

    OE_TBL -->|CDC INSERT events| WAL
    WAL --> SRC
    SRC --> XFM
    XFM --> HDRMAPPER
    HDRMAPPER -->|key=aggregate_id\nvalue=Avro payload| T1
    KF <-->|schema validation| SR
    DEZ <--> OS

    style DEZ fill:#f0f4ff
    style KF fill:#fff8e1
```

**Delivery guarantees:**
- `enable.idempotence=true` + `acks=all` + `retries=10` → **effectively-once** production from Debezium
- Consumers use idempotency check on `eventId` → **at-least-once** consumed safely

---

## 6. Layer 4 — Compute Side: Schedule Service

**Port:** 8082 (app) · 8090 (actuator)
**Key files:** `services/schedule-service/src/main/java/com/railway/platform/schedule/`

```mermaid
sequenceDiagram
    participant KF as Kafka Topic<br/>railway.timetable.changed
    participant CON as TimetableChangedConsumer<br/>@KafkaListener
    participant PROP as KafkaCorrelationIdPropagator
    participant IDEM as ProcessedEventRepository
    participant SCS as ScheduleComputationService
    participant DB as schedule_db (PostgreSQL)
    participant PROD as ScheduleComputedProducer
    participant KF2 as Kafka Topic<br/>railway.schedule.computed

    KF->>CON: TimetableChangedEvent (Avro)
    note over CON: @Transactional("kafkaTransactionManager")\nDB writes + Kafka publish atomic

    CON->>PROP: extract correlationId from record headers
    PROP->>PROP: MDC.put(correlationId)

    CON->>IDEM: existsByEventId(event.eventId)?
    alt duplicate event
        IDEM-->>CON: true → log WARN, ack, return
    end

    CON->>SCS: compute(timetableChangedEvent, correlationId)
    note over SCS: @Timed schedule.computation.duration
    SCS->>SCS: computeServices(event) → services list
    SCS-->>CON: ScheduleComputedEvent

    CON->>DB: save(ComputedScheduleEntity)
    CON->>IDEM: save(ProcessedEventEntity{eventId})
    note over IDEM: UNIQUE constraint — race-safe

    CON->>PROD: publish(ScheduleComputedEvent)
    PROD->>KF2: Avro record\nheader: correlation_id={correlationId}
    note over KF2: Within same Kafka transaction

    CON->>KF: acknowledge offset
    CON->>PROP: MDC.clear in finally

    note over CON: DefaultErrorHandler:\nnon-retryable → railway.dlq\nretryable → 3× exponential backoff
```

**Resilience4j circuit breaker** wraps Kafka producer calls:
- `sliding-window-size: 5`, `failure-rate-threshold: 50%`, `wait-duration-in-open-state: 30s`

---

## 7. Layer 5 — Read Side: Query Service

**Port:** 8083 (app) · 8090 (actuator)
**Key files:** `services/query-service/src/main/java/com/railway/platform/query/`

### 7.1 Event Projection Flow

```mermaid
sequenceDiagram
    participant KF as Kafka Topic<br/>railway.timetable.changed
    participant PROJ as TimetableProjector<br/>@KafkaListener
    participant IDEM as ProcessedEventRepository
    participant RM as TimetableReadModelRepository (JPA)
    participant DB as query_db (PostgreSQL)
    participant CACHE as TimetableCacheService (Redis)

    KF->>PROJ: TimetableChangedEvent (Avro)
    note over PROJ: @Transactional — DB only

    PROJ->>IDEM: existsByEventId?
    alt duplicate
        IDEM-->>PROJ: true → ack, return
    end

    PROJ->>RM: findById(timetableId)
    RM-->>PROJ: existing TimetableReadModelEntity (or empty)

    PROJ->>RM: save(UPSERT full state from event)
    note over DB: INSERT ON CONFLICT DO UPDATE

    PROJ->>IDEM: save(ProcessedEventEntity{eventId})
    PROJ->>KF: acknowledge offset
    PROJ->>CACHE: evict(timetableId)
    note over CACHE: Evict AFTER ack — ensures\nnext read sees fresh data
    PROJ->>PROJ: MDC.clear in finally
```

### 7.2 Cache-Aside Read Path

```mermaid
flowchart TD
    CLIENT[GET /api/v1/timetables/:id] --> CTL[TimetableQueryController]
    CTL --> QS[TimetableQueryService.getById]

    QS --> CGET[TimetableCacheService.get\nkey: timetable:{timetableId}]

    CGET --> HIT{Redis hit?}
    HIT -->|yes| RET1[return TimetableView\nfrom cache]
    HIT -->|no| DB[TimetableReadModelRepository.findById\nquery_db PostgreSQL]

    DB --> FOUND{found?}
    FOUND -->|no| E404[throw NotFoundException → 404]
    FOUND -->|yes| MAP[map entity → TimetableView]

    MAP --> TTL{status?}
    TTL -->|ACTIVE / EMERGENCY_ACTIVE| T5m[TTL = 5 minutes]
    TTL -->|DRAFT / PENDING_REVIEW| T30s[TTL = 30 seconds]
    TTL -->|APPROVED / REJECTED / SUPERSEDED / CANCELLED| T10m[TTL = 10 minutes]

    T5m & T30s & T10m --> PUT[TimetableCacheService.put\nStringRedisTemplate JSON]
    PUT --> RET2[return TimetableView]

    subgraph PAGED["GET /api/v1/lines/:lineId/timetables?page=0&size=20&status=ACTIVE"]
        PQ[listByLine\npage max 100] --> PDB[Repository query\nwith optional status filter]
        PDB --> PMAP["PagedResponse.of(content, page, size, totalElements)\ntotalPages = ceil(total/size)\nlast = page >= totalPages-1"]
    end
```

---

## 8. Layer 6 — Distribution / Fan-Out: Distribution Service

**Port:** 8084 (app + WebSocket) · 8090 (actuator)
**Key files:** `services/distribution-service/src/main/java/com/railway/platform/distribution/`

### 8.1 Saga Coordination Flow

```mermaid
sequenceDiagram
    participant KF as Kafka Topic<br/>railway.schedule.computed
    participant CON as ScheduleComputedConsumer<br/>@KafkaListener
    participant ORCH as DistributionOrchestrator
    participant WSD as WebSocketDistributor
    participant PAD as PassengerAppDistributor
    participant SDD as StationDisplayDistributor
    participant PFD as PartnerFeedDistributor
    participant STOMP as SimpMessagingTemplate<br/>(STOMP broker)
    participant NKAF as Kafka Topic<br/>railway.notification.requests
    participant TKAF as Kafka Topic<br/>railway.distribution.events
    participant DB as distribution_db

    KF->>CON: ScheduleComputedEvent
    note over CON: @Transactional("kafkaTransactionManager")

    CON->>CON: idempotency check on eventId

    CON->>ORCH: distribute(event, correlationId)

    note over ORCH: Fan-out channels in order:

    ORCH->>WSD: distribute(event)
    WSD->>STOMP: convertAndSend\n/topic/lines/{lineId}/schedule\n{timetableId, lineId, effectiveDate,\ntriggeringEventId, isEmergency}
    alt isEmergency
        WSD->>STOMP: convertAndSend\n/topic/emergency\n{broadcast to ALL subscribers}
    end
    WSD-->>ORCH: DistributionResult(WEBSOCKET_PUSH, success=true)

    ORCH->>PAD: distribute(event)
    PAD->>NKAF: NotificationRequestEvent\n{recipientIds, channels, template,\ntemplate vars, isEmergency}
    PAD-->>ORCH: DistributionResult(PASSENGER_APP, success=true)

    ORCH->>SDD: distribute(event)
    SDD-->>ORCH: DistributionResult(STATION_DISPLAY, ...)

    ORCH->>PFD: distribute(event)
    PFD-->>ORCH: DistributionResult(PARTNER_FEED, ...)

    note over ORCH: Saga compensation check:
    ORCH->>ORCH: allNonWsFailed?
    alt all non-WS channels failed
        ORCH->>TKAF: DistributionEvent{status=COMPENSATED}
    else normal outcome
        ORCH->>TKAF: DistributionEvent{status=PUBLISHED, channel=...}\nper channel
    end

    ORCH->>DB: save(DistributionTrackingEntity)\nper-channel success/failure record

    CON->>DB: save(ProcessedEventEntity{eventId})
    CON->>KF: acknowledge offset
```

### 8.2 WebSocket / STOMP Layer

```mermaid
flowchart TD
    subgraph WS["WebSocketConfig (distribution-service)"]
        EP[STOMP endpoint: /ws\nwith SockJS fallback]
        MB[In-memory SimpleBroker\n/topic prefix]
        APP[Application destination prefix: /app]
        USER[User destination prefix: /user]
    end

    subgraph SUBS["Subscription Topics"]
        S1["/topic/lines/{lineId}/schedule\nline-specific schedule updates"]
        S2["/topic/emergency\nbroadcast emergency to all clients"]
    end

    subgraph CLIENT["Angular Operator Console"]
        STOMP_CLIENT["@stomp/stompjs Client\nreconnectDelay: 5000ms"]
        CONN_SIG["connected = signal&lt;boolean&gt;(false)"]
        STORE["NgRx Signals Store\nonScheduleUpdate() handler"]
    end

    EP --> MB
    MB --> S1
    MB --> S2
    S1 <-->|subscribe| STOMP_CLIENT
    S2 <-->|subscribe| STOMP_CLIENT
    STOMP_CLIENT --> CONN_SIG
    STOMP_CLIENT --> STORE

    note1[/"Auth: JWT passed as\n?access_token= query param\non /ws handshake"/]
```

---

## 9. Layer 7 — Notifications: Notification Service

**Port:** 8085 (app) · 8090 (actuator)
**Key files:** `services/notification-service/src/main/java/com/railway/platform/notification/`

```mermaid
sequenceDiagram
    participant KF as Kafka Topic<br/>railway.notification.requests
    participant CON as NotificationRequestConsumer<br/>@KafkaListener
    participant NDS as NotificationDeliveryService
    participant TR as TemplateRenderer
    participant PUSH as PushNotificationProvider<br/>(Firebase FCM)
    participant SMS as SmsNotificationProvider<br/>(Twilio)
    participant EMAIL as EmailNotificationProvider<br/>(SendGrid / SES)
    participant DB as notification_db

    KF->>CON: NotificationRequestEvent (Avro)
    note over CON: @Transactional — DB only

    CON->>CON: idempotency check on eventId
    CON->>CON: check scheduleAfter timestamp\n(log WARN if future — scheduler in Phase 5)

    CON->>NDS: deliver(event, correlationId)

    NDS->>TR: render(template, templateVariables)
    TR-->>NDS: {title, body}

    loop for each NotificationChannel (PUSH, SMS, EMAIL)
        NDS->>NDS: resolve provider from\nMap&lt;NotificationChannel, NotificationProvider&gt;

        alt PUSH channel
            NDS->>PUSH: send(recipientIds, title, body,\nisEmergency, correlationId)
            PUSH->>PUSH: elevate priority if isEmergency
            PUSH-->>NDS: delivery result
        else SMS channel
            NDS->>SMS: send(phoneNumbers, title, body, ...)
            SMS-->>NDS: delivery result
        else EMAIL channel
            NDS->>EMAIL: send(emailAddresses, title, body, ...)
            EMAIL-->>NDS: delivery result
        end

        NDS->>DB: INSERT INTO sent_notifications\n(recipient, channel, status,\ncorrelation_id, sent_at)
    end

    note over NDS: Counters:\nnotification.deliveries.total{channel}\nnotification.delivery.errors.total{channel, reason}
    note over NDS: Partial delivery tolerated:\nfailure in one channel does NOT\nstop others

    CON->>DB: save(ProcessedEventEntity{eventId})
    CON->>KF: acknowledge offset
```

---

## 10. Layer 8 — Observability Stack

```mermaid
flowchart TD
    subgraph SVCS["All 6 Services (:8090/actuator)"]
        PROM_EP[/actuator/prometheus\nMicrometer Prometheus registry]
        HEALTH_EP[/actuator/health\nliveness · readiness · kafka · outboxLag]
        TRACES[OTel spans\nmicrometer-tracing-bridge-otel\nopentelemetry-exporter-otlp]
        LOGS[Structured logs\nlogback-spring.xml\nSpringProfile k8s → ECS JSON\nSpringProfile !k8s → plain-text]
    end

    subgraph ALLOY["Grafana Alloy (OTel Collector)"]
        OTLP_RCV[OTLP receiver\n:4317 gRPC · :4318 HTTP]
        LOG_SCRAPE[Log tail / file scrape\nfrom pod stdout]
    end

    subgraph TEMPO["Grafana Tempo"]
        TRACE_STORE[Distributed traces\nW3C traceparent correlation]
    end

    subgraph LOKI["Grafana Loki"]
        LOG_STORE[Log aggregation\nlabels: service, environment, pod]
    end

    subgraph PROM["Prometheus"]
        SCRAPE[Scrape :8090/actuator/prometheus\nevery 15s] --> TSDB[Time-series DB\n15-day retention]
    end

    subgraph GRAFANA["Grafana 12.x Dashboards"]
        D1[platform-overview\nerror rates · latency · deploy events]
        D2[kafka-consumer-lag\nper-group per-topic lag]
        D3[timetable-service\ncommand latency · HikariCP · approvals]
        D4[jvm-overview\nheap · GC pause · thread counts]
    end

    subgraph K8S["Kubernetes Probes"]
        LP[Liveness: GET :8090/actuator/health/liveness]
        RP[Readiness: GET :8090/actuator/health/readiness]
    end

    TRACES --> ALLOY
    LOGS --> ALLOY
    ALLOY --> TEMPO
    ALLOY --> LOKI
    PROM_EP --> PROM
    TEMPO --> GRAFANA
    LOKI --> GRAFANA
    PROM --> GRAFANA
    HEALTH_EP --> K8S

    subgraph CORR["Correlation ID Propagation Chain"]
        C1[HTTP X-Correlation-Id header]
        C2[MDC correlationId → log field]
        C3[Kafka record header correlation_id]
        C4[OTel W3C traceparent]
        C1 --> C2 --> C3 --> C4
    end
```

---

## 11. Layer 9 — Infrastructure: Helm + Terraform + CI/CD

### 11.1 Helm Chart Structure (per service)

```mermaid
flowchart TD
    subgraph CHART["infra/helm/{service}/"]
        VALS[values.yaml\ndefault values]
        VALS_P[values-prod.yaml\nproduction overrides]
        subgraph TEMPLATES["templates/"]
            DEP[deployment.yaml\nVault Agent sidecar annotations\nreadOnlyRootFilesystem\nallowPrivilegeEscalation=false\ncapabilities.drop=[ALL]]
            SVC[service.yaml\nClusterIP app:808x + actuator:8090]
            HPA[hpa.yaml\nCPU 70% target\nmin/max replicas]
            PDB[pdb.yaml\nPodDisruptionBudget\nminAvailable from values]
            NP[networkpolicy.yaml\ndeny-all default\nexplicit egress: DNS·Postgres·Kafka·Redis·Vault·OTel\napi-gateway ingress: allow all]
            CM[configmap.yaml\nSPRING_PROFILES_ACTIVE=k8s\nJAVA_OPTS]
            ING[ingress.yaml\nALB annotations\nACM cert ARN\n(api-gateway only)]
        end
    end

    subgraph VAULT_SIDECAR["Vault Agent Sidecar Injector"]
        VA[vault.hashicorp.com/agent-inject: true\nvault.hashicorp.com/role: {service}\nwrites to /vault/secrets/application.properties\nas in-memory emptyDir]
        BOOT[Spring Boot loads via\nSPRING_CONFIG_IMPORT env var\nspring.cloud.vault.enabled=false in k8s profile]
    end

    DEP --> VAULT_SIDECAR
```

### 11.2 Terraform Module Dependency Graph

```mermaid
graph LR
    VPC["vpc\nSubnets, Security Groups\nNAT Gateway, Route Tables"]

    EKS["eks\nEKS 1.31 cluster\nSystem + App node groups\nOIDC provider for IRSA"]

    RDS["rds\nPostgreSQL 16 (multi-AZ)\nwal_level=logical\nencrypted, deletion_protection=true\npassword → Secrets Manager"]

    MSK["msk\nKafka 3.7 SASL/SCRAM\nKRaft (no Zookeeper)\nSchemaRegistry"]

    EC["elasticache\nRedis cluster\nauth + encryption in transit"]

    ECR["ecr\nOne repository per service\nimage scanning enabled\nlifecycle: keep last 30 images"]

    VPC --> EKS
    VPC --> RDS
    VPC --> MSK
    VPC --> EC
    EKS --> ECR
```

### 11.3 CI/CD Pipeline Flow

```mermaid
flowchart TD
    subgraph PUSH["Git Event"]
        PR[Pull Request\nservices/** shared/** pom.xml]
        MAIN[Push to main]
        DISPATCH[Manual dispatch\nor schedule weekly]
    end

    subgraph CI_B["ci-backend.yml"]
        MAT[Maven matrix build\n6 services in parallel\nfail-fast: false]
        MAT --> UT[mvn test\nunit tests — no Docker]
        MAT --> IT[mvn verify -Dgroups=integration\nTestcontainers: Postgres + Kafka + Redis]
        IT --> ECR_PUSH[docker build\ndocker push :git-sha\nto ECR — main only]
    end

    subgraph CI_F["ci-frontend.yml"]
        NG_BUILD[ng build --configuration=production]
        NG_TEST[ng test --browsers=ChromeHeadless\nKarma + Jasmine]
        NG_LINT[ESLint flat config]
    end

    subgraph SEC["security-scan.yml"]
        OWASP[OWASP Dependency Check\nNVD API key — avoids rate limit]
        TRIVY[Trivy image scanning\nCRITICAL/HIGH fail build]
        TH[TruffleHog secret scanning]
    end

    subgraph CD["cd-deploy.yml"]
        DEV_AUTO[Auto-deploy to dev\non every main push]
        PROD_MANUAL[Manual dispatch\nGitHub Environment approval required]
        DEV_AUTO & PROD_MANUAL --> HELM_DEPLOY
        subgraph HELM_DEPLOY["Helm Deploy (sequential per service)"]
            H1[helm upgrade --install\n--atomic --wait --timeout 5m]
            H1 --> H2[Health check\nK8s liveness :8090]
            H2 --> SLACK[Slack webhook\ndeploy notification]
        end
    end

    subgraph DEP["dependabot.yml"]
        MVN_UP[Maven weekly\nignore Spring Boot/Cloud major]
        NPM_UP[npm weekly\nignore Angular/NgRx major]
        GHA_UP[GitHub Actions weekly]
    end

    PR --> CI_B
    PR --> CI_F
    PR --> SEC
    MAIN --> CI_B
    MAIN --> CI_F
    MAIN --> CD
    DISPATCH --> SEC
```

---

## 12. End-to-End Flow: Timetable Change to Passenger Notification

This trace follows a single timetable approval from operator click to push notification, showing every layer in sequence with latency budgets.

```mermaid
sequenceDiagram
    participant OP as Operator Browser<br/>(Angular 21)
    participant GW as API Gateway :8080
    participant TS as Timetable Service :8081
    participant PGTS as timetable_db
    participant DEZ as Debezium CDC
    participant KF1 as Kafka<br/>railway.timetable.changed
    participant SS as Schedule Service :8082
    participant KF2 as Kafka<br/>railway.schedule.computed
    participant QS as Query Service :8083
    participant REDIS as Redis
    participant DS as Distribution Service :8084
    participant WS as WebSocket STOMP
    participant KF3 as Kafka<br/>railway.notification.requests
    participant NS as Notification Service :8085
    participant FCM as Firebase FCM

    Note over OP,FCM: ① HTTP Write Path (~50ms)

    OP->>GW: POST /api/v1/timetables/{id}/approve\nAuthorization: Bearer {JWT ROLE_TIMETABLE_APPROVER}
    GW->>GW: JWT validate, RBAC check, rate limit, circuit breaker
    GW->>TS: POST /api/v1/timetables/{id}/approve\n+ X-Correlation-Id header
    TS->>TS: CorrelationIdFilter extracts/mints correlationId
    TS->>PGTS: SELECT timetable (optimistic lock)
    TS->>TS: Timetable.approve() validates state machine\nPENDING_REVIEW → APPROVED
    TS->>PGTS: UPDATE timetables (version++)\nINSERT outbox_events{status=APPROVED}\nINSERT audit_log  ← single ACID commit
    TS->>GW: 200 OK {timetableId, status: APPROVED}
    GW->>OP: 200 OK

    Note over OP,FCM: ② Debezium CDC (~100ms)

    PGTS->>DEZ: WAL event: outbox_events INSERT
    DEZ->>KF1: TimetableChangedEvent(changeType=APPROVED)\nkey=timetableId, header correlation_id

    Note over OP,FCM: ③ Query Projection + Cache Evict (~200ms)

    KF1->>QS: TimetableChangedEvent
    QS->>QS: idempotency check
    QS->>QS: UPSERT TimetableReadModelEntity(status=APPROVED)
    QS->>REDIS: evict(timetableId)
    QS->>KF1: ack offset

    Note over OP,FCM: ④ Schedule Computation (~300ms)

    KF1->>SS: TimetableChangedEvent
    SS->>SS: idempotency check
    SS->>SS: computeServices(event)
    SS->>KF2: ScheduleComputedEvent\n(within Kafka transaction)
    SS->>KF1: ack offset

    Note over OP,FCM: ⑤ Distribution Fan-Out (~500ms total, WebSocket first)

    KF2->>DS: ScheduleComputedEvent
    DS->>DS: idempotency check
    DS->>WS: STOMP /topic/lines/{lineId}/schedule\n{timetableId, lineId, effectiveDate, isEmergency=false}
    WS->>OP: real-time update received (~50ms sub-path)
    OP->>OP: NgRx Signals Store.onScheduleUpdate()\nUI re-renders with new timetable status

    DS->>KF3: NotificationRequestEvent\n{recipientIds, channels=[PUSH,SMS,EMAIL],\ntemplate=TIMETABLE_APPROVED}
    DS->>KF2: ack offset

    Note over OP,FCM: ⑥ Notification Dispatch (~1-2s total)

    KF3->>NS: NotificationRequestEvent
    NS->>NS: idempotency check
    NS->>NS: TemplateRenderer.render(template, vars)
    NS->>FCM: send PUSH to passenger recipientIds
    FCM->>FCM: deliver to passenger apps
    NS->>NS: record SentNotificationEntity per recipient
    NS->>KF3: ack offset

    Note over OP,FCM: Total latency: ≤5s p95 (per platform SLA)
```

---

## 13. End-to-End Flow: Emergency Activation

Emergency activation bypasses the normal approval workflow entirely.

```mermaid
sequenceDiagram
    participant OP as Emergency Operator<br/>(ROLE_EMERGENCY_OPERATOR)
    participant GW as API Gateway
    participant TS as Timetable Service
    participant KF as Kafka
    participant DS as Distribution Service
    participant WS as WebSocket STOMP
    participant NS as Notification Service
    participant FCM as FCM / SMS

    OP->>GW: POST /api/v1/timetables/{id}/emergency-activate\n{justification: "track 3 incident"}
    GW->>GW: RBAC: require ROLE_EMERGENCY_OPERATOR only\n(separate highest-priority route)

    GW->>TS: POST .../emergency-activate

    Note over TS: Timetable.activateEmergency(actor, justification)
    Note over TS: ApprovalStateMachine allows:\nDRAFT → EMERGENCY_ACTIVE\nPENDING_REVIEW → EMERGENCY_ACTIVE
    TS->>TS: INSERT outbox_events{changeType=EMERGENCY_ACTIVATED}\nINSERT audit_log{justification=...} ← same ACID commit

    TS->>GW: 200 OK
    GW->>OP: 200 OK

    TS->>KF: CDC → TimetableChangedEvent(changeType=EMERGENCY_ACTIVATED)

    KF->>DS: ScheduleComputedEvent [after schedule-service]

    DS->>WS: /topic/lines/{lineId}/schedule {isEmergency=true}
    DS->>WS: /topic/emergency {broadcast ALL subscribers}
    Note over WS: Emergency broadcast goes to ALL\noperator consoles, not just lineId subscribers

    DS->>KF: NotificationRequestEvent{isEmergency=true}

    KF->>NS: NotificationRequestEvent

    NS->>FCM: FCM with HIGH priority flag\n(bypasses Doze mode on Android)
    NS->>FCM: SMS via Twilio (immediate)
    Note over NS: isEmergency=true elevates\nprovider priority / rate limits
```

---

## Appendix: Idempotency Pattern (All Consumers)

Every Kafka consumer in the platform follows the identical idempotency recipe:

```mermaid
flowchart TD
    MSG[Kafka Message arrives] --> EXT[Extract correlationId\nfrom record headers]
    EXT --> CHECK{processed_events\ncontains eventId?}

    CHECK -->|YES — duplicate| LOG[log WARN duplicate eventId]
    LOG --> ACK_D[ack offset — safe to skip]

    CHECK -->|NO — new event| PROC[process business logic]
    PROC --> SAVE_PE["INSERT INTO processed_events\n(event_id, topic, processed_at)\nUNIQUE constraint on event_id"]

    SAVE_PE --> RACE{DataIntegrityViolation?\nrace between consumers}
    RACE -->|yes| LOG2[treat as duplicate — safe]
    RACE -->|no| ACK[ack offset]

    PROC --> DLQ_CHECK{non-retryable exception?}
    DLQ_CHECK -->|yes| DLQ[DefaultErrorHandler\n→ railway.dlq\nwith original topic header]
    DLQ_CHECK -->|no — transient| RETRY[retry 3× exponential backoff\n100ms · 200ms · 400ms]
```

---

*Document generated from source code analysis of `claude/railway-timetable-platform-8bkan`. All class names, file paths, topic names, and configuration values reflect the actual implementation.*
