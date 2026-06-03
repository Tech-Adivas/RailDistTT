# Prediction Service

Python microservice that consumes `railway.schedule.computed` Avro events from
Kafka, applies an ML model to produce delay predictions, and publishes
`railway.delay.prediction.computed` Avro events back to Kafka.

Exposes `/health` and `/metrics` on port 8000.

---

## Local development

### Prerequisites

- Python 3.11+
- A running Kafka broker and Schema Registry (use `make up` from the repo root)
- (Optional) Redis for idempotency cache

### Set up a virtual environment

```bash
cd services/prediction-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
pip install -r requirements-dev.txt
pip install -e src/   # installs prediction_service as editable package
```

### Configure local environment

Create a `.env` file (never commit it):

```dotenv
KAFKA_BOOTSTRAP_SERVERS=localhost:29092
SCHEMA_REGISTRY_URL=http://localhost:8081
VAULT_ENABLED=false
MLFLOW_ENABLED=false
LOG_LEVEL=DEBUG
```

### Run the service

```bash
python -m prediction_service.main
```

The service starts on `http://localhost:8000`.

- `GET /health` — dependency health status
- `GET /metrics` — Prometheus metrics
- `POST /admin/reload-model` — hot-reload the ML model (requires `Authorization: Bearer <token>`)

---

## Running tests

### Unit tests (no Docker required)

```bash
pytest tests/unit/
```

### Integration tests (requires Docker)

Integration tests spin up real Kafka, Schema Registry, and Redis containers via
Testcontainers. Docker must be running.

```bash
pytest -m integration tests/integration/
```

---

## Architecture

```
Kafka (railway.schedule.computed)
        │
        ▼
 PredictionConsumer
        │  deserialise (Avro)
        │  idempotency check (Redis / memory)
        ▼
 InferenceEngine
        │  FeatureExtractor → numpy array
        │  ML model (MLflow Production / mock)
        ▼
 PredictionEventProducer
        │  serialise (Avro)
        ▼
Kafka (railway.delay.prediction.computed)
```

All secrets are stored in HashiCorp Vault and injected at runtime via the Vault
Agent Sidecar Injector. See `docs/CONFIGURATION.md` for the full placeholder
reference.
