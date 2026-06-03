# Technical Design Document — Python AI Prediction Service

**Document status:** Draft  
**Author:** Platform Architecture  
**Date:** 2026-06-03  
**Related requirements:** `requirements.md` (Python LLM/ML Integration)  
**ADRs to raise:** ADR-011 (Python service in JVM platform), ADR-012 (ML Model Registry choice)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Goals and Non-Goals](#2-goals-and-non-goals)
3. [System Architecture](#3-system-architecture)
4. [Technology Stack](#4-technology-stack)
5. [Service Module Structure](#5-service-module-structure)
6. [Component Design](#6-component-design)
7. [Data Design](#7-data-design)
8. [API Design](#8-api-design)
9. [Resilience Design](#9-resilience-design)
10. [Security Design](#10-security-design)
11. [Observability Design](#11-observability-design)
12. [ML Model Lifecycle](#12-ml-model-lifecycle)
13. [Infrastructure Design](#13-infrastructure-design)
14. [Integration with Existing Services](#14-integration-with-existing-services)
15. [CI/CD Pipeline](#15-cicd-pipeline)
16. [Cross-Cutting Concerns](#16-cross-cutting-concerns)
17. [Open Questions and Risks](#17-open-questions-and-risks)

---

## 1. Executive Summary

This document describes the design for the **Python AI Prediction Service** (`prediction-service`), a new microservice added to the RailDistTT platform that generates ML-based delay predictions for railway routes.

The service consumes `railway.schedule.computed` events from Kafka, applies a trained machine learning model to extract delay forecasts, and publishes `railway.delay.prediction.computed` events. Downstream, the existing Query Service is extended to expose predictions via a read API, the Distribution Service fans predictions out to the operator console via WebSocket and to partner feeds, and Grafana gains a dedicated dashboard.

The service is written in Python 3.11 and must integrate seamlessly with the Java/Spring Boot platform while preserving all existing reliability guarantees: zero acknowledged-write loss, idempotent consumers, and ≤5 s end-to-end event propagation (p95).

---

## 2. Goals and Non-Goals

### Goals

- Consume schedule events idempotently and generate delay predictions within 500 ms (p95) inference time.
- Publish prediction events as Avro-serialised Kafka messages with BACKWARD-compatible schemas.
- Integrate with HashiCorp Vault for all secrets — no secrets in code, config files, or images.
- Expose `/health` and `/metrics` endpoints compatible with Kubernetes probes and Prometheus scraping.
- Support graceful shutdown within Kubernetes `terminationGracePeriodSeconds: 30`.
- Extend the Query Service read model with a `delay_predictions` table and `GET /api/v1/predictions` endpoint.
- Distribute predictions to the Angular operator console via existing WebSocket/STOMP infrastructure.
- Include predictions in Distribution Service partner feed payloads.
- Ship a Grafana dashboard and alert rules for the new service.

### Non-Goals

- Model training — this TDD covers inference only. Training pipelines are out of scope.
- Real-time weather API integration — the circuit-breaker design accommodates it, but the weather provider is not specified here.
- Passenger-facing push/SMS/email notifications for predictions — that is a future enhancement on top of the notification-service.
- Replacing or modifying the Transactional Outbox pattern — the new service publishes directly to Kafka (not via an outbox) because it is not a write-side aggregate. At-least-once Kafka producer semantics are sufficient.

---

## 3. System Architecture

### 3.1 Context: How the Prediction Service Fits In

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              RailDistTT Platform                                │
│                                                                                 │
│  ┌──────────────┐  Outbox CDC   ┌──────────────────────────────────────────┐   │
│  │  Timetable   │──────────────▶│            Apache Kafka                  │   │
│  │  Service     │               │                                          │   │
│  └──────────────┘               │  railway.timetable.changed               │   │
│                                 │  railway.schedule.computed  ◀────────┐   │   │
│  ┌──────────────┐               │  railway.delay.prediction.computed    │   │   │
│  │  Schedule    │──────────────▶│  railway.distribution.events          │   │   │
│  │  Service     │               │  railway.notification.requests        │   │   │
│  └──────────────┘               │  railway.dlq                          │   │   │
│                                 │  railway.delay.prediction.dlq  (NEW)  │   │   │
│                                 └──────────────────┬───────────────────┘   │   │
│                                                    │                        │   │
│              ┌─────────────────────────────────────┘                        │   │
│              │  railway.schedule.computed                                    │   │
│              ▼                                                               │   │
│  ┌───────────────────────────┐                                              │   │
│  │  Python AI Prediction     │  railway.delay.prediction.computed ──────────┘   │
│  │  Service  (NEW)           │──────────────────────────────────────────────▶   │
│  │  port 8000                │                                                  │
│  │  Python 3.11 / FastAPI    │                                                  │
│  │  MLflow Model Registry    │                                                  │
│  └───────────────────────────┘                                                  │
│                                                                                 │
│  ┌──────────────┐  Projection   ┌──────────────────────────────────────────┐   │
│  │  Query       │◀──────────────│  delay.prediction.computed consumer      │   │
│  │  Service     │               │  (PredictionQueryProjector - NEW)        │   │
│  │  port 8083   │               └──────────────────────────────────────────┘   │
│  └──────────────┘                                                               │
│        │  GET /api/v1/predictions (NEW)                                         │
│        ▼                                                                        │
│  ┌──────────────┐  WebSocket    ┌──────────────────────────────────────────┐   │
│  │ Distribution │──────────────▶│  Angular Operator Console                │   │
│  │ Service      │  /topic/      │  (prediction panel - NEW)                │   │
│  │ port 8084    │  predictions  └──────────────────────────────────────────┘   │
│  └──────────────┘                                                               │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 End-to-End Prediction Flow

```
  Operator                Kafka                  Python AI              Kafka
  Console          schedule.computed          Pred. Service    delay.prediction.computed
     │                    │                        │                      │
     │                    │──── event ────────────▶│                      │
     │                    │                        │ 1. Dedup on eventId  │
     │                    │                        │ 2. Extract features  │
     │                    │                        │ 3. ML inference      │
     │                    │                        │    (≤500ms p95)      │
     │                    │                        │ 4. Validate output   │
     │                    │                        │ 5. Build Avro event  │
     │                    │                        │──── publish ────────▶│
     │                    │                        │ 6. Commit offset     │
     │                    │                        │                      │
     │                    │              Query Service projects           │
     │                    │              delay_predictions table          │
     │                    │                        │                      │
     │◀─── WebSocket push (Distribution Service) ──────────────────────  │
     │  predicted delay + confidence + colour      │                      │
```

### 3.3 Graceful Shutdown Flow

```
  Kubernetes           Python AI Prediction Service
     │                         │
     │── SIGTERM ─────────────▶│
     │                         │ 1. Pause Kafka consumer (no new msgs)
     │                         │ 2. Drain in-flight predictions (max 10s)
     │                         │ 3. Flush Kafka producer
     │                         │ 4. Commit final offsets
     │                         │ 5. Close Kafka / Vault / DB connections
     │                         │ 6. Exit 0
     │                         │
     │  (if >30s total)        │
     │── SIGKILL ─────────────▶│ (forced)
```

---

## 4. Technology Stack

| Concern | Choice | Rationale |
|---|---|---|
| Runtime | Python 3.11 | Matches requirements; broad ML ecosystem |
| Web framework | **FastAPI** 0.111+ | Async, Pydantic validation, auto OpenAPI, lightweight |
| Kafka client | **confluent-kafka-python** 2.4+ | C-librdkafka base; production-grade; used for both consumer and producer |
| Avro serialization | **confluent-kafka[avro]** with `AvroDeserializer` / `AvroSerializer` | Integrates with existing Confluent Schema Registry |
| Vault client | **hvac** 2.x | Mature Python Vault client; supports Kubernetes auth and lease renewal |
| Prometheus metrics | **prometheus-client** 0.20+ | Standard; exposes `/metrics` in text format |
| Circuit breaker | **pybreaker** 1.x | Lightweight; thread-safe; supports CLOSED/OPEN/HALF_OPEN states |
| ML inference (default) | **scikit-learn** 1.5+ | Lightweight, fast inference; serialized via joblib |
| ML inference (optional) | **PyTorch** 2.x | For neural network models; loaded conditionally |
| Model Registry | **MLflow** 2.x (Tracking Server) | Versioned artifact storage; production tag query; REST API |
| Idempotency cache | **Redis** (via `redis-py` 5.x) | Matches platform's existing Redis; consistent with query-service caching |
| Configuration | **pydantic-settings** 2.x | Env-var binding with type validation; no secrets in files |
| Structured logging | **structlog** 24.x | Native JSON output; context binding for correlationId |
| Testing | **pytest** 8.x + **testcontainers-python** 4.x | Integration tests with real Kafka + Schema Registry containers |
| Container base | `python:3.11-slim` (Debian Bookworm slim) | Minimises attack surface; well-maintained |

---

## 5. Service Module Structure

```
services/prediction-service/
├── Dockerfile
├── requirements.txt                   # pinned with --hash (pip-compile output)
├── requirements-dev.txt               # pytest, testcontainers, ruff, mypy
├── pyproject.toml                     # project metadata, tool config (ruff, mypy, pytest)
├── README.md
│
├── src/
│   └── prediction_service/
│       ├── __init__.py
│       ├── main.py                    # FastAPI app factory + lifespan (startup/shutdown)
│       ├── config.py                  # pydantic-settings Settings class
│       │
│       ├── kafka/
│       │   ├── __init__.py
│       │   ├── consumer.py            # KafkaConsumer wrapper (confluent-kafka)
│       │   ├── producer.py            # KafkaProducer wrapper with retry + flush
│       │   ├── avro_serde.py          # AvroSerializer / AvroDeserializer factory
│       │   └── dlq.py                 # DLQ publisher (raw bytes + error headers)
│       │
│       ├── inference/
│       │   ├── __init__.py
│       │   ├── engine.py              # InferenceEngine: load model, run prediction
│       │   ├── model_registry.py      # MLflowModelRegistry client
│       │   └── feature_extractor.py   # ScheduleComputedEvent → feature vector
│       │
│       ├── vault/
│       │   ├── __init__.py
│       │   └── client.py              # VaultClient: auth, secret fetch, lease renewal
│       │
│       ├── resilience/
│       │   ├── __init__.py
│       │   └── circuit_breaker.py     # CircuitBreakerManager (wraps pybreaker)
│       │
│       ├── idempotency/
│       │   ├── __init__.py
│       │   └── cache.py               # ProcessedEventCache (Redis-backed)
│       │
│       ├── api/
│       │   ├── __init__.py
│       │   ├── health.py              # GET /health
│       │   ├── metrics.py             # GET /metrics (prometheus_client exposition)
│       │   └── admin.py               # POST /admin/reload-model (ADMIN role)
│       │
│       └── observability/
│           ├── __init__.py
│           ├── logging.py             # structlog JSON renderer + correlationId binding
│           └── metrics.py             # All prometheus_client metric definitions
│
├── tests/
│   ├── unit/
│   │   ├── test_feature_extractor.py
│   │   ├── test_inference_engine.py
│   │   ├── test_circuit_breaker.py
│   │   └── test_health.py
│   └── integration/
│       ├── conftest.py                # Testcontainers: Kafka + Schema Registry + Redis
│       ├── test_consumer_producer.py  # End-to-end consume → infer → publish
│       └── test_dlq.py
│
└── k8s/
    ├── deployment.yaml
    ├── service.yaml
    ├── serviceaccount.yaml
    └── configmap.yaml
```

---

## 6. Component Design

### 6.1 Configuration (`config.py`)

All configuration is read from environment variables. Secrets are injected by Vault Agent Sidecar or `hvac` at runtime; no defaults are permitted for secret values.

```python
class Settings(BaseSettings):
    # Kafka
    kafka_bootstrap_servers: str         # from Vault
    kafka_sasl_username: str             # from Vault
    kafka_sasl_password: SecretStr       # from Vault — never logged
    kafka_consumer_group: str = "railway.prediction-service"
    kafka_topic_schedule: str = "railway.schedule.computed"
    kafka_topic_timetable: str = "railway.timetable.changed"
    kafka_topic_prediction: str = "railway.delay.prediction.computed"
    kafka_topic_dlq: str = "railway.delay.prediction.dlq"

    # Schema Registry
    schema_registry_url: str             # from Vault or ConfigMap

    # Vault
    vault_addr: str                      # VAULT_ADDR env var
    vault_role: str                      # VAULT_ROLE env var
    vault_k8s_mount: str = "kubernetes"

    # MLflow Model Registry
    mlflow_tracking_uri: str             # from Vault
    mlflow_model_name: str = "delay-predictor"
    mlflow_model_stage: str = "Production"

    # Redis (idempotency cache)
    redis_host: str                      # from Vault
    redis_port: int = 6379
    redis_password: SecretStr            # from Vault
    redis_processed_events_ttl: int = 86400  # seconds

    # Service
    log_level: str = "INFO"
    port: int = 8000
    circuit_breaker_failure_threshold: int = 5
    circuit_breaker_recovery_timeout: int = 30
    kafka_producer_retry_attempts: int = 3

    model_config = SettingsConfigDict(env_file=None, case_sensitive=False)
```

### 6.2 Kafka Consumer (`kafka/consumer.py`)

The consumer runs in a dedicated thread, polling in a loop. The main event-processing pipeline is:

```
poll() → deserialize Avro → check idempotency cache
       → extract features → run inference → build PredictionEvent
       → publish to delay.prediction.computed → store eventId in cache
       → commit offset
```

Key design decisions:

- **`enable.auto.commit = false`** — offsets committed manually after full processing or DLQ publish.
- **`isolation.level = read_committed`** — only reads messages from committed transactions (consistent with Java consumers).
- **`auto.offset.reset = earliest`** — ensures no events are missed on first deployment.
- The consumer loop catches all exceptions at message level; a single bad message never stops the loop.

```
┌────────────────────────────────────────────────────────────┐
│                    Consumer Loop                           │
│                                                            │
│  poll(timeout=1.0)                                         │
│       │                                                    │
│       ▼                                                    │
│  Avro deserialize ──── error ──▶ DLQ + commit offset       │
│       │                                                    │
│       ▼                                                    │
│  idempotency check ─── duplicate ──▶ commit offset (skip) │
│       │                                                    │
│       ▼                                                    │
│  feature extraction                                        │
│       │                                                    │
│       ▼                                                    │
│  ML inference ──────── error ──▶ fallback prediction       │
│       │                          (confidence=0.0)          │
│       ▼                                                    │
│  validate output                                           │
│       │                                                    │
│       ▼                                                    │
│  publish Prediction_Event                                  │
│       │                                                    │
│       ▼                                                    │
│  store eventId in Redis                                    │
│       │                                                    │
│       ▼                                                    │
│  commit offset                                             │
└────────────────────────────────────────────────────────────┘
```

### 6.3 Feature Extractor (`inference/feature_extractor.py`)

Transforms a `ScheduleComputedEvent` into a numeric feature vector for the ML model.

| Feature | Source | Type |
|---|---|---|
| `line_id_encoded` | `ScheduleComputedEvent.lineId` (label-encoded) | int |
| `effective_day_of_week` | `effectiveDate` | int (0–6) |
| `effective_month` | `effectiveDate` | int (1–12) |
| `service_count` | `len(services)` | int |
| `earliest_departure_minutes` | min `departureTime` across all stops | int |
| `latest_arrival_minutes` | max `arrivalTime` across all stops | int |
| `stops_count_avg` | average stops per service | float |
| `maintenance_affected_ratio` | fraction of services with `affectedByMaintenance=true` | float |
| `historical_delay_p50`* | lookup from Redis/DB by lineId | float |
| `weather_disruption_score`* | external API (circuit-breaker protected) | float |

\* Optional features — absent features are replaced with the model's trained mean imputation value.

### 6.4 Inference Engine (`inference/engine.py`)

```
┌─────────────────────────────────────────────────────┐
│                 InferenceEngine                     │
│                                                     │
│  _model: sklearn.Pipeline (or nn.Module)            │
│  _model_version: str                                │
│  _model_lock: threading.RLock                       │
│                                                     │
│  load_model(version="latest")                       │
│    └─ MLflowModelRegistry.get_production_model()    │
│    └─ validate artifact schema                      │
│    └─ acquire lock → swap _model                    │
│    └─ update Prometheus model_version_info gauge    │
│                                                     │
│  predict(features: np.ndarray) → DelayPrediction   │
│    └─ acquire lock (read)                           │
│    └─ model.predict() + model.predict_proba()       │
│    └─ validate: delay_minutes ≥ 0, 0.0 ≤ conf ≤ 1  │
│    └─ record model_inference_duration_seconds       │
└─────────────────────────────────────────────────────┘
```

- Model artifacts are stored in MLflow as `sklearn.Pipeline` (joblib) or `torch.nn.Module` (TorchScript).
- On load failure, the engine keeps the previous working model and logs `ERROR`. It does not swap to a broken model.
- Hot-reload is triggered by `POST /admin/reload-model` which calls `engine.load_model()` under a write lock.

### 6.5 Kafka Producer (`kafka/producer.py`)

- Uses `confluent_kafka.Producer` with `acks=all` and `enable.idempotence=true`.
- Retry logic: 3 attempts with exponential backoff (1 s, 2 s, 4 s) implemented at application layer (separate from librdkafka internal retries).
- `producer.flush(timeout=10)` called on graceful shutdown.
- `correlationId` propagated as Kafka record header `X-Correlation-Id`.

### 6.6 Vault Client (`vault/client.py`)

```
Startup sequence:
  1. Detect auth method (K8s service account token at
     /var/run/secrets/kubernetes.io/serviceaccount/token, or AppRole)
  2. hvac.Client.auth.kubernetes.login(role=VAULT_ROLE, jwt=token)
  3. Fetch secrets from:
       secret/data/railway/prediction-service/kafka
       secret/data/railway/prediction-service/redis
       secret/data/railway/prediction-service/mlflow
  4. Schedule lease renewal at 75% of lease TTL
  5. If Vault unreachable: retry 5× (2s, 4s, 8s, 16s, 32s), then sys.exit(1)

Runtime:
  - Background thread calls hvac renew_self() before lease expiry
  - On renewal failure: log CRITICAL, update /health to DEGRADED
```

Vault secret paths:

| Path | Keys |
|---|---|
| `secret/data/railway/prediction-service/kafka` | `bootstrap_servers`, `sasl_username`, `sasl_password` |
| `secret/data/railway/prediction-service/redis` | `host`, `password` |
| `secret/data/railway/prediction-service/mlflow` | `tracking_uri`, `access_key`, `secret_key` |

### 6.7 Circuit Breaker (`resilience/circuit_breaker.py`)

Wraps calls to optional external dependencies (weather API, historical delay lookup service) using `pybreaker.CircuitBreaker`:

```
Configuration per breaker:
  fail_max = 5          (consecutive failures before OPEN)
  reset_timeout = 30    (seconds in OPEN before HALF_OPEN)

State machine:
  CLOSED ──(5 failures)──▶ OPEN
  OPEN   ──(30s timeout)──▶ HALF_OPEN
  HALF_OPEN ──(success)──▶ CLOSED
  HALF_OPEN ──(failure)──▶ OPEN
```

Fallback behaviour when OPEN: feature value replaced with trained mean imputation; prediction proceeds with reduced confidence.

Prometheus `circuit_breaker_state` gauge is updated on every state transition:
- `0` = CLOSED
- `1` = OPEN
- `2` = HALF_OPEN

### 6.8 Idempotency Cache (`idempotency/cache.py`)

Redis-backed with a 24-hour TTL per `eventId`. Interface:

```python
class ProcessedEventCache:
    def is_duplicate(self, event_id: str) -> bool
    def mark_processed(self, event_id: str) -> None
```

On Redis unavailability, the cache degrades to in-memory (bounded LRU, 50 000 entries) and logs a WARNING. Processing continues — idempotency is best-effort in Redis-down scenarios, consistent with the rest of the platform's approach (the `processed_events` DB table in Java services serves the same role).

### 6.9 Dead Letter Queue (`kafka/dlq.py`)

DLQ topic: `railway.delay.prediction.dlq`

Headers appended to every DLQ message:

| Header | Value |
|---|---|
| `original-topic` | e.g. `railway.schedule.computed` |
| `original-partition` | partition number as string |
| `original-offset` | offset as string |
| `error-message` | exception class + message (truncated at 500 chars) |
| `error-timestamp` | ISO 8601 UTC |
| `correlation-id` | from consumed event header |

After DLQ publish succeeds, the original offset is committed.

---

## 7. Data Design

### 7.1 Avro Schema — `delay-prediction-event.avsc`

Location: `shared/events/src/main/avro/delay-prediction-event.avsc`

```json
{
  "namespace": "com.railway.platform.events",
  "name": "DelayPredictionEvent",
  "type": "record",
  "doc": "Emitted by Python AI Prediction Service after computing a delay prediction from a ScheduleComputedEvent. Published to railway.delay.prediction.computed.",
  "fields": [
    {
      "name": "metadata",
      "type": "com.railway.platform.events.EventMetadata",
      "doc": "Standard event metadata. correlationId inherited from the triggering ScheduleComputedEvent."
    },
    {
      "name": "routeId",
      "type": "string",
      "doc": "Railway line identifier, e.g. LINE-VIC-BRI. Matches lineId in ScheduleComputedEvent."
    },
    {
      "name": "trainId",
      "type": ["null", "string"],
      "default": null,
      "doc": "Specific train/service ID if prediction is service-level. Null for line-level predictions."
    },
    {
      "name": "predictedDelayMinutes",
      "type": "int",
      "doc": "Predicted delay in whole minutes. 0 means on time. Negative values are clamped to 0."
    },
    {
      "name": "confidenceScore",
      "type": "float",
      "doc": "Model confidence in [0.0, 1.0]. 0.0 indicates no prediction available (fallback)."
    },
    {
      "name": "modelVersion",
      "type": "string",
      "doc": "Semantic version of the ML model that produced this prediction, e.g. v1.2.0."
    },
    {
      "name": "triggeringScheduleEventId",
      "type": "string",
      "doc": "eventId of the ScheduleComputedEvent that triggered this prediction — for traceability."
    }
  ]
}
```

**Schema Registry subject:** `railway.delay.prediction.computed-value`  
**Compatibility mode:** `BACKWARD`  
All future fields must carry a `default` value (ADR-004).

The Python service registers this schema on startup and resolves the schema ID at publish time. The Java Topics constant `DELAY_PREDICTION_COMPUTED = "railway.delay.prediction.computed"` must be added to `shared/events/.../Topics.java`.

### 7.2 Database Schema — `delay_predictions` Table

Added to `query_db` via a new Flyway migration in `query-service`:

```sql
-- V5__create_delay_predictions.sql

CREATE TABLE delay_predictions (
    prediction_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id            VARCHAR(255) NOT NULL,
    train_id            VARCHAR(255),
    predicted_delay_minutes INT     NOT NULL CHECK (predicted_delay_minutes >= 0),
    confidence_score    FLOAT       NOT NULL CHECK (confidence_score >= 0.0 AND confidence_score <= 1.0),
    model_version       VARCHAR(50) NOT NULL,
    predicted_at        TIMESTAMPTZ NOT NULL,
    event_id            VARCHAR(255) NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delay_predictions_route_id     ON delay_predictions (route_id);
CREATE INDEX idx_delay_predictions_train_id     ON delay_predictions (train_id) WHERE train_id IS NOT NULL;
CREATE INDEX idx_delay_predictions_predicted_at ON delay_predictions (predicted_at DESC);
CREATE INDEX idx_delay_predictions_event_id     ON delay_predictions (event_id);
```

### 7.3 Kafka Topics

| Topic | Consumed by | Produced by | Partitions | Retention |
|---|---|---|---|---|
| `railway.schedule.computed` | prediction-service (new consumer) | schedule-service | 12 (existing) | 7 days |
| `railway.delay.prediction.computed` | query-service (new projector), distribution-service | prediction-service | 6 | 7 days |
| `railway.delay.prediction.dlq` | Ops / manual replay | prediction-service | 3 | 7 days |

Consumer group for prediction-service: `railway.prediction-service`

### 7.4 Redis Cache Keys

| Key pattern | TTL | Purpose |
|---|---|---|
| `pred:processed:{eventId}` | 86 400 s | Idempotency — marks a consumed eventId as processed |
| `cache:prediction:{routeId}` | 60 s | Query-service read cache for `GET /api/v1/predictions` |
| `cache:prediction:{routeId}:{trainId}` | 60 s | Train-level prediction cache |

---

## 8. API Design

### 8.1 `GET /health` (port 8000)

Used for Kubernetes liveness and readiness probes.

**Response — all healthy (HTTP 200):**
```json
{
  "status": "UP",
  "kafka_consumer": "UP",
  "kafka_producer": "UP",
  "vault": "UP",
  "model": "UP",
  "redis": "UP"
}
```

**Response — partial failure (HTTP 503):**
```json
{
  "status": "DOWN",
  "kafka_consumer": "DOWN",
  "kafka_producer": "UP",
  "vault": "UP",
  "model": "UP",
  "redis": "UP"
}
```

- Must respond within 100 ms. Uses cached dependency status updated every 5 s; does not call external systems on each probe.
- The check for each dependency tests only connectivity, not correctness (e.g. ping Kafka broker, check Vault token validity, verify model is loaded).

### 8.2 `GET /metrics` (port 8000)

Prometheus text format exposition. See Section 11 for metric definitions.

### 8.3 `POST /admin/reload-model` (port 8000)

Protected by OIDC JWT with `ADMIN` role claim.

**Request:** `POST /admin/reload-model` with `Authorization: Bearer <token>`  
**Response (HTTP 200):**
```json
{
  "status": "ok",
  "previous_version": "v1.1.0",
  "new_version": "v1.2.0",
  "loaded_at": "2026-06-03T10:30:00Z"
}
```

**Response — model load failure (HTTP 500):**
```json
{
  "status": "error",
  "message": "Model artifact corrupted or schema incompatible. Service continues with v1.1.0.",
  "current_version": "v1.1.0"
}
```

### 8.4 `GET /api/v1/predictions` (query-service, port 8083)

New endpoint added to the existing `TimetableQueryController` or a new `PredictionQueryController`.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `routeId` | string | Yes | Railway line identifier, e.g. `LINE-VIC-BRI` |
| `trainId` | string | No | Filter by specific service ID |
| `effectiveDate` | date (ISO 8601) | No | Filter by prediction date |

**Response (HTTP 200):**
```json
{
  "predictions": [
    {
      "routeId": "LINE-VIC-BRI",
      "trainId": "SVC-VIC-BRI-0730",
      "predictedDelayMinutes": 7,
      "confidenceScore": 0.82,
      "modelVersion": "v1.2.0",
      "predictedAt": "2026-06-03T10:15:00Z"
    }
  ]
}
```

**Response — no data (HTTP 200, not 404):**
```json
{ "predictions": [] }
```

**RBAC:** `READ_ONLY` role or higher (enforced at API Gateway and query-service `@PreAuthorize`).  
**Latency target:** 300 ms p99 (Redis cache serves most requests within 5 ms).  
**Caching:** 60-second Redis TTL; cache key `cache:prediction:{routeId}` (or with `trainId`).

---

## 9. Resilience Design

### 9.1 Circuit Breaker State Machine

```
                 ┌─────────────────────────────────────────┐
                 │         External Dependency              │
                 │    (weather API / hist. delay service)   │
                 └─────────────────────────────────────────┘
                              │
                 ┌────────────▼──────────┐
         success │       CLOSED          │ 5 consecutive failures
    ◀────────────│  Normal operation     │────────────────────────▶
                 │  Track failures       │
                 └───────────────────────┘
                              ▲
                              │ success               ┌───────────────────────┐
                              │                       │       OPEN            │
                 ┌────────────┴──────────┐            │  Reject immediately   │
                 │      HALF_OPEN        │ failure    │  Return fallback      │
                 │  Allow 1 test request │────────────│  prediction           │
                 │                       │            │  Wait 30s             │
                 └───────────────────────┘            └───────────────────────┘
                              ▲                                  │
                              └──────────── 30s timeout ─────────┘
```

### 9.2 Retry Strategies

| Operation | Strategy | Attempts | Delays |
|---|---|---|---|
| Vault connection at startup | Exponential backoff | 5 | 2s, 4s, 8s, 16s, 32s |
| Kafka producer publish | Exponential backoff | 3 | 1s, 2s, 4s |
| Query-service DB on projection failure | Exponential backoff | 3 | 100ms, 200ms, 400ms |
| Vault lease renewal | Fixed interval at 75% of TTL | Continuous | — |

### 9.3 Fallback Prediction

When inference cannot be completed (model error, circuit-breaker OPEN, invalid output):

```json
{
  "routeId": "<routeId from event>",
  "trainId": null,
  "predictedDelayMinutes": 0,
  "confidenceScore": 0.0,
  "modelVersion": "<current loaded version>",
  "triggeringScheduleEventId": "<eventId>"
}
```

A `confidenceScore` of `0.0` is the universal signal to consumers that no meaningful prediction is available. The UI must treat `confidenceScore == 0.0` as "prediction unavailable" and render a neutral indicator rather than "0 minutes delay".

### 9.4 Graceful Shutdown

```python
# Signal handler registered at startup
async def on_shutdown():
    consumer.pause()                 # stop polling new messages
    await asyncio.wait_for(          # drain in-flight
        drain_task, timeout=10.0
    )
    producer.flush(timeout=10.0)     # flush pending Kafka messages
    consumer.commit()                # commit final offsets
    consumer.close()
    producer.close()
    vault_client.close()
    redis_client.close()
    sys.exit(0)
```

`terminationGracePeriodSeconds: 30` (Kubernetes) + `preStop: sleep 5` = 25 s effective window.

---

## 10. Security Design

### 10.1 Vault Integration

```
┌──────────────────────────────────────────────────────────┐
│          Kubernetes Pod — prediction-service              │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  prediction-service container                   │    │
│  │                                                 │    │
│  │  hvac.Client                                    │    │
│  │    auth: kubernetes (serviceaccount token)      │    │
│  │    role: prediction-service                     │    │
│  │    mount: kubernetes                            │    │
│  │                                                 │    │
│  │  Secrets fetched at startup:                    │    │
│  │    kafka credentials                            │    │
│  │    redis credentials                            │    │
│  │    mlflow credentials                           │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  ServiceAccount: prediction-service-sa                   │
│  (bound to Vault Kubernetes auth role)                   │
└──────────────────────────────────────────────────────────┘
         │  Vault Kubernetes auth
         ▼
┌─────────────────────┐
│   HashiCorp Vault   │
│                     │
│  Vault Policy:      │
│  prediction-service │
│  read:              │
│    secret/data/     │
│    railway/         │
│    prediction-      │
│    service/*        │
└─────────────────────┘
```

Vault policy to add to `infra/vault/policies/prediction-service.hcl`:

```hcl
path "secret/data/railway/prediction-service/*" {
  capabilities = ["read"]
}
path "auth/token/renew-self" {
  capabilities = ["update"]
}
```

### 10.2 Container Security

- Runs as UID 1000 (non-root).
- `securityContext.readOnlyRootFilesystem: true` — write access only to `/tmp`.
- `securityContext.allowPrivilegeEscalation: false`.
- `securityContext.capabilities.drop: ["ALL"]`.

### 10.3 Secret Handling Rules

- `SecretStr` (Pydantic) prevents accidental `str()` or `repr()` exposure.
- structlog `structlog.processors.ExceptionRenderer` is configured to strip known secret field names.
- `/admin/reload-model` validates OIDC JWT signature and `ADMIN` role before executing. Token validation uses the API Gateway's public JWKS endpoint (OIDC discovery).

---

## 11. Observability Design

### 11.1 Prometheus Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `predictions_generated_total` | Counter | `model_version`, `status` (success/failure) | Total predictions produced |
| `prediction_errors_total` | Counter | `error_type` (inference_error, schema_error, poison_message, vault_error) | Error counter by type |
| `kafka_messages_consumed_total` | Counter | `topic` | Messages consumed |
| `kafka_messages_published_total` | Counter | `topic`, `status` (success/failure) | Messages published |
| `model_inference_duration_seconds` | Histogram | `model_version` | Inference latency; buckets: 0.05, 0.1, 0.25, 0.5, 1.0 |
| `kafka_publish_duration_seconds` | Histogram | — | Kafka produce latency |
| `kafka_consumer_lag` | Gauge | `topic`, `partition` | Messages behind latest offset |
| `model_version_info` | Gauge | `version` | Currently loaded model (always 1.0, label carries version) |
| `circuit_breaker_state` | Gauge | `dependency` | 0=CLOSED, 1=OPEN, 2=HALF_OPEN |
| Standard Python runtime metrics | — | — | Via `prometheus_client.start_http_server` default collectors |

### 11.2 Structured JSON Log Format

Every log line is a JSON object on a single line:

```json
{
  "timestamp": "2026-06-03T10:15:30.123Z",
  "level": "INFO",
  "message": "Prediction generated",
  "service_name": "prediction-service",
  "component": "inference.engine",
  "correlation_id": "6ba7b810-9dad-11d1-80b4-00c04fd430cb",
  "route_id": "LINE-VIC-BRI",
  "predicted_delay_minutes": 7,
  "confidence_score": 0.82,
  "model_version": "v1.2.0",
  "inference_duration_ms": 312
}
```

structlog configuration binds `correlation_id` from the Kafka header at the start of each event processing context and clears it on completion.

Fields **never** logged: Kafka credentials, Redis password, MLflow access key, full Kafka message payload.

### 11.3 Grafana Dashboard (`prediction-service`)

Dashboard panels:

| Panel | Type | Query |
|---|---|---|
| Predictions per minute | Time series | `rate(predictions_generated_total[1m])` |
| Inference latency p50/p95/p99 | Time series | `histogram_quantile(0.95, rate(model_inference_duration_seconds_bucket[5m]))` |
| Consumer lag | Gauge + time series | `kafka_consumer_lag` |
| Error rate by type | Time series | `rate(prediction_errors_total[5m])` by `error_type` |
| Circuit breaker state | State timeline | `circuit_breaker_state` coloured green/red/yellow |
| Active model version | Stat | `model_version_info` label |
| Confidence score distribution | Heatmap | histogram of `confidence_score` from prediction events |

### 11.4 Alert Rules

| Alert | Expression | For | Severity |
|---|---|---|---|
| `PredictionServiceDown` | `absent(predictions_generated_total)` | 2 min | critical |
| `PredictionHighConsumerLag` | `kafka_consumer_lag > 1000` | 5 min | warning |
| `PredictionHighErrorRate` | `rate(prediction_errors_total[5m]) / rate(predictions_generated_total[5m]) > 0.05` | 5 min | warning |
| `PredictionDLQSpike` | `rate(kafka_messages_published_total{topic="railway.delay.prediction.dlq"}[10m]) > 0.0083` (>5 msgs/10min) | 0 min | warning |
| `PredictionCircuitBreakerOpen` | `circuit_breaker_state == 1` | 1 min | warning |
| `PredictionModelNotLoaded` | `model_version_info == 0` | 1 min | critical |

Alert rules are deployed as a `PrometheusRule` CRD in `infra/observability/prediction-service-alerts.yaml`.

---

## 12. ML Model Lifecycle

### 12.1 Model Registry (MLflow)

```
Model lifecycle in MLflow:

  Data Scientist                 MLflow Tracking Server
       │                                │
       │── train model ────────────────▶│  run_id, metrics (MAE, RMSE)
       │── log artifact ───────────────▶│  model.pkl / model.pt + schema
       │── register model ────────────▶│  delay-predictor / v1.2.0
       │── set stage "Staging" ────────▶│
       │                                │
       │  (validation by ML team)       │
       │                                │
       │── set stage "Production" ─────▶│
       │                                │
       │                  prediction-service polls
       │                  on startup:
       │                  get_model(name="delay-predictor",
       │                            stage="Production")
```

Model metadata stored per version:

| Field | Type | Example |
|---|---|---|
| `modelVersion` | string | `v1.2.0` |
| `trainingDate` | ISO 8601 | `2026-05-15` |
| `performanceMetrics.MAE` | float | `3.2` (minutes) |
| `performanceMetrics.RMSE` | float | `5.1` (minutes) |
| `schemaVersion` | int | `1` |
| `trainingDataset` | string | `s3://railway-ml/datasets/2026-Q1` |
| `framework` | string | `sklearn` or `pytorch` |

### 12.2 Model Loading at Startup

```
1. Query MLflow: get latest model with stage="Production"
2. Download artifact to /tmp/models/<version>/
3. Load: joblib.load() for sklearn, torch.load() for PyTorch
4. Validate: run inference on a known fixture, check output shape
5. Register as active model, update model_version_info gauge
6. If any step fails: log CRITICAL, raise exception → service fails to start
```

### 12.3 Hot Reload via `POST /admin/reload-model`

```
1. Validate OIDC JWT (ADMIN role)
2. Fetch latest "Production" model from MLflow (may be same or newer)
3. Load into a shadow InferenceEngine instance
4. Run validation inference on fixture
5. acquire write lock → swap _model + _model_version
6. release lock
7. Update model_version_info Prometheus gauge
8. Log INFO: "Model hot-reloaded: v1.1.0 → v1.2.0"
9. Return 200 with old/new version
```

If the new model fails validation, the swap does not occur and `503` is returned. The service continues serving the previous model.

---

## 13. Infrastructure Design

### 13.1 Dockerfile

```dockerfile
# Stage 1: build — install dependencies with hash verification
FROM python:3.11-slim AS builder
WORKDIR /build
COPY requirements.txt .
RUN pip install --upgrade pip \
 && pip install --require-hashes --no-cache-dir -r requirements.txt \
      --target /build/deps

# Stage 2: runtime — minimal image, non-root user
FROM python:3.11-slim AS runtime
WORKDIR /app

# Create non-root user UID=1000
RUN groupadd -r appgroup --gid 1000 \
 && useradd -r -g appgroup --uid 1000 --home /app appuser

COPY --from=builder /build/deps /app/deps
COPY src/ /app/src/

ENV PYTHONPATH=/app/deps:/app/src
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# Configurable via Kubernetes ConfigMap / Vault injection
ENV KAFKA_BOOTSTRAP_SERVERS=""
ENV VAULT_ADDR=""
ENV VAULT_ROLE=""
ENV LOG_LEVEL="INFO"

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8000/health || exit 1

USER appuser

CMD ["python", "-m", "prediction_service.main"]
```

Target image size: < 500 MB (python:3.11-slim ~130 MB + scikit-learn + confluent-kafka ~200 MB = ~350 MB).

### 13.2 Kubernetes Manifests (`services/prediction-service/k8s/`)

**`configmap.yaml`** — non-secret configuration only:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: prediction-service-config
data:
  KAFKA_TOPIC_SCHEDULE: "railway.schedule.computed"
  KAFKA_TOPIC_PREDICTION: "railway.delay.prediction.computed"
  KAFKA_TOPIC_DLQ: "railway.delay.prediction.dlq"
  KAFKA_CONSUMER_GROUP: "railway.prediction-service"
  LOG_LEVEL: "INFO"
  MLFLOW_MODEL_NAME: "delay-predictor"
  MLFLOW_MODEL_STAGE: "Production"
  REDIS_PORT: "6379"
```

**`serviceaccount.yaml`** — for Vault Kubernetes auth:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: prediction-service-sa
  annotations:
    # TODO(config): Terraform output: prediction_service_iam_role_arn
    eks.amazonaws.com/role-arn: "PLACEHOLDER_IAM_ROLE_ARN_PREDICTION_SERVICE"
```

**`deployment.yaml`** — key excerpts:

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  template:
    spec:
      serviceAccountName: prediction-service-sa
      terminationGracePeriodSeconds: 30
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        runAsNonRoot: true
      containers:
        - name: prediction-service
          image: PLACEHOLDER_ECR_REGISTRY/railway-platform/prediction-service:latest
          ports:
            - containerPort: 8000
          resources:
            requests:
              cpu: "500m"
              memory: "1Gi"
            limits:
              cpu: "2000m"
              memory: "2Gi"
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 5"]
          livenessProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 30
            periodSeconds: 10
            failureThreshold: 3
            timeoutSeconds: 5
          readinessProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 3
            timeoutSeconds: 5
          startupProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 10
            periodSeconds: 10
            failureThreshold: 30
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
```

**`service.yaml`**:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: prediction-service
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/port: "8000"
    prometheus.io/path: "/metrics"
spec:
  type: ClusterIP
  selector:
    app: prediction-service
  ports:
    - name: http
      port: 8000
      targetPort: 8000
```

**PodDisruptionBudget:**

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: prediction-service-pdb
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app: prediction-service
```

**NetworkPolicy:** Allow ingress from Prometheus scraper and API Gateway only; allow egress to Kafka, Redis, Vault, MLflow, Schema Registry.

---

## 14. Integration with Existing Services

### 14.1 `shared/events` Changes

1. Add `delay-prediction-event.avsc` (see Section 7.1).
2. Add to `Topics.java`:
   ```java
   public static final String DELAY_PREDICTION_COMPUTED = "railway.delay.prediction.computed";
   public static final String DELAY_PREDICTION_DLQ = "railway.delay.prediction.dlq";
   ```
3. Run `make generate-events` to regenerate the `DelayPredictionEvent` Java POJO.

### 14.2 `query-service` Changes

**New files:**

- `infrastructure/persistence/entity/DelayPredictionEntity.java` — JPA entity for `delay_predictions` table.
- `infrastructure/persistence/repository/DelayPredictionRepository.java` — `findByRouteIdAndTrainIdAndPredictedAtAfter`.
- `projector/PredictionQueryProjector.java` — `@KafkaListener` on `DELAY_PREDICTION_COMPUTED`; deduplicates on `eventId`; upserts `delay_predictions`; invalidates `cache:prediction:{routeId}` Redis keys.
- `application/PredictionQueryService.java` — query with Redis cache (60 s TTL).
- `api/rest/PredictionQueryController.java` — `GET /api/v1/predictions`.
- `api/dto/PredictionView.java` — response DTO.
- `db/migration/V5__create_delay_predictions.sql` — Flyway migration (see Section 7.2).

**Modified files:**

- `config/KafkaConsumerConfig.java` — add `ConcurrentKafkaListenerContainerFactory` bean for `DelayPredictionEvent` deserialization.

### 14.3 `distribution-service` Changes

**Modified `ScheduleComputedConsumer.java`:** No change — the existing consumer continues unchanged.

**New `PredictionDistributor.java`:** Consumes `railway.delay.prediction.computed`, deduplicates on `eventId`, and:

1. Serialises `DelayPredictionEvent` as JSON.
2. Broadcasts to STOMP topic `/topic/predictions` via `SimpMessagingTemplate`.
3. Caches latest prediction per `routeId` with 60 s TTL for inclusion in partner feed payloads.

**Modified `PartnerFeedDistributor.java`:** Queries `GET /api/v1/predictions?routeId={routeId}` (via `WebClient`, 60 s cached) and appends the prediction block to each route in the feed. If no prediction available, the fields are omitted (not null). Adds disclaimer to feed metadata:

> "Delay predictions are AI-generated and may not reflect actual delays. Use for informational purposes only."

### 14.4 Angular Operator Console Changes

**New `PredictionPanelComponent`:** Subscribes to STOMP `/topic/predictions`. On each message received:

- Display `predictedDelayMinutes` and `confidenceScore` in the timetable detail view.
- Visual indicator: green (`< 5 min`), yellow (`5–15 min`), red (`> 15 min`).
- When `confidenceScore == 0.0`: display "Prediction unavailable" (neutral grey).
- Unsubscribe on component destroy.

STOMP subscription path: `/topic/predictions` on WebSocket endpoint `/predictions` (matches existing WebSocket infrastructure in `WebSocketConfig.java`).

---

## 15. CI/CD Pipeline

### 15.1 New CI Workflow — `ci-prediction.yml`

Triggered on changes to `services/prediction-service/**` or `shared/events/**`:

```
Step 1: Python lint + type check
  └─ ruff check src/ tests/
  └─ mypy src/

Step 2: Unit tests
  └─ pytest tests/unit/ -v --tb=short

Step 3: Integration tests (Testcontainers)
  └─ pytest tests/integration/ -v --tb=short -m integration
     (spins up Kafka + Schema Registry + Redis containers)

Step 4: Avro schema compatibility check
  └─ POST /compatibility/subjects/railway.delay.prediction.computed-value/versions/latest
     to Schema Registry with new schema
  └─ Fail build if INCOMPATIBLE

Step 5: Docker build + Trivy scan
  └─ docker build -t prediction-service:$GITHUB_SHA .
  └─ trivy image --severity HIGH,CRITICAL --exit-code 1 prediction-service:$GITHUB_SHA

Step 6: Push to ECR (main branch only)
  └─ docker tag prediction-service:$GITHUB_SHA
        $ECR_REGISTRY/railway-platform/prediction-service:$GITHUB_SHA
  └─ docker tag ... :latest
  └─ docker push both tags
```

### 15.2 CD Deployment

Added to existing `cd-deploy.yml` as a new step after Java services:

```yaml
- name: Deploy prediction-service
  run: |
    kubectl set image deployment/prediction-service \
      prediction-service=$ECR_REGISTRY/railway-platform/prediction-service:${{ github.sha }}
    kubectl rollout status deployment/prediction-service --timeout=5m
  # On failure: kubectl rollout undo deployment/prediction-service
```

Rollback is automatic via `kubectl rollout undo` if `rollout status` times out after 5 minutes.

---

## 16. Cross-Cutting Concerns

### 16.1 Correlation ID Propagation

```
HTTP request to API Gateway
  └─ X-Correlation-Id header set (or generated)
       └─ timetable-service writes to outbox with correlationId
            └─ Kafka header: X-Correlation-Id
                 └─ schedule-service inherits, publishes new event with same correlationId
                      └─ Kafka header: X-Correlation-Id
                           └─ prediction-service reads header
                                └─ binds to structlog context variable
                                     └─ all log lines carry correlation_id
                                └─ included in DelayPredictionEvent.metadata.correlationId
                                └─ included in Kafka header on published prediction event
                                     └─ query-service projector → delay_predictions.correlation_id
                                     └─ distribution-service → WebSocket payload
```

### 16.2 Idempotency

Every Kafka consumer in the platform deduplicates on `eventId`. The prediction service follows the same pattern using Redis (with in-memory LRU fallback). This means:

- A prediction service restart with re-delivered events will not re-publish duplicate `DelayPredictionEvent`s.
- The query-service `PredictionQueryProjector` deduplicates on `eventId` with a `UNIQUE` constraint on `delay_predictions.event_id`.
- The distribution-service `PredictionDistributor` deduplicates via its `processed_events` table (same pattern as `ScheduleComputedConsumer`).

### 16.3 Schema Evolution Rules

Future additions to `DelayPredictionEvent` must follow ADR-004:

1. New fields must have a `default` value (`null` for optional fields, typed defaults for required fields).
2. Existing fields must not be removed or renamed.
3. Field types must not change in a BACKWARD-incompatible way.
4. The CI schema compatibility check (Section 15.1, Step 4) enforces this automatically.

---

## 17. Open Questions and Risks

| # | Question / Risk | Impact | Owner | Resolution |
|---|---|---|---|---|
| 1 | **ML Model Registry choice**: MLflow is specified in requirements glossary. If the team prefers a managed service (SageMaker Model Registry, Vertex AI), the `ModelRegistry` interface in `inference/model_registry.py` abstracts the client — swap the implementation without changing the engine. | Medium | ML team | Decide before implementation starts; raise ADR-012 |
| 2 | **Weather API provider**: The circuit-breaker design is in place, but no weather provider is specified. Until a provider is chosen, the `weather_disruption_score` feature defaults to `0.0` (mean imputation). | Low | Data team | Deferred to Phase 2 |
| 3 | **Python service in Helm**: The existing Helm charts are Java-specific (actuator ports, JVM flags). The prediction-service ships its own standalone Kubernetes manifests in `services/prediction-service/k8s/` and a separate Helm chart in `infra/helm/prediction-service/`. | Low | DevOps | Align with platform Helm standards |
| 4 | **Prediction accuracy SLA**: The requirements specify a 500 ms inference SLA but no accuracy threshold. Without a minimum confidence floor, low-quality predictions may be presented to operators. Recommend suppressing predictions with `confidenceScore < 0.3`. | High | Product | Define accuracy SLA before go-live |
| 5 | **Consumer group lag at first deployment**: On first deployment, `auto.offset.reset=earliest` will process all historical `schedule.computed` events. For a busy line this could be millions of events. Recommend setting `auto.offset.reset=latest` for production first deployment, then switching to `earliest` for subsequent restarts. | Medium | DevOps | Handled in deployment runbook |
| 6 | **Read-model query latency under load**: `GET /api/v1/predictions` hitting query-service under high load could bottleneck. The 60 s Redis cache should absorb most traffic; if not, consider moving predictions to a dedicated read endpoint backed by a separate read replica. | Low-Medium | Platform | Monitor after launch |
| 7 | **Schema Registry subject naming**: Python `confluent-kafka` uses `<topic>-value` naming convention. Verify this aligns with the Java `KafkaAvroSerializer` subject naming strategy configured on existing topics. | Low | Platform | Verify during integration test |
