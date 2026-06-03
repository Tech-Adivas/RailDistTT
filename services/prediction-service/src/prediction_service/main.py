"""FastAPI application entry point for the Prediction Service.

Wires all singletons, manages the service lifecycle via the FastAPI ``lifespan``
context manager, and registers a SIGTERM handler for graceful shutdown.
"""

from __future__ import annotations

import asyncio
import signal
import sys
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI

from prediction_service.api import admin as admin_module
from prediction_service.api import health as health_module
from prediction_service.api.admin import router as admin_router
from prediction_service.api.health import router as health_router
from prediction_service.api.metrics import router as metrics_router
from prediction_service.config import get_settings
from prediction_service.idempotency.cache import ProcessedEventCache
from prediction_service.inference.engine import InferenceEngine
from prediction_service.inference.feature_extractor import FeatureExtractor
from prediction_service.inference.model_registry import ModelRegistry
from prediction_service.kafka.avro_serde import AvroSerde
from prediction_service.kafka.consumer import PredictionConsumer
from prediction_service.kafka.dlq import DlqPublisher
from prediction_service.kafka.producer import PredictionEventProducer
from prediction_service.observability.logging import configure_logging, get_logger
from prediction_service.resilience.circuit_breaker import CircuitBreakerManager
from prediction_service.vault.client import VaultClient

# ---------------------------------------------------------------------------
# Bootstrap settings (module-level so they can be imported elsewhere)
# ---------------------------------------------------------------------------

settings = get_settings()

# Delay logger creation until after configure_logging is called; we create it
# here so the module can be imported without side-effects.
logger = get_logger(__name__)

# ---------------------------------------------------------------------------
# Singleton dependency graph
# ---------------------------------------------------------------------------

vault_client = VaultClient(settings)
cache = ProcessedEventCache(settings)
serde = AvroSerde(settings.schema_registry_url)

import confluent_kafka  # noqa: E402 — after settings bootstrap

_raw_producer = confluent_kafka.Producer(
    {
        "bootstrap.servers": settings.kafka_bootstrap_servers,
        # SASL applied below if username is set
        **({
            "security.protocol": "SASL_SSL",
            "sasl.mechanism": "SCRAM-SHA-512",
            "sasl.username": settings.kafka_sasl_username,
            "sasl.password": settings.kafka_sasl_password.get_secret_value(),
        } if settings.kafka_sasl_username else {}),
        "acks": "all",
        "enable.idempotence": True,
        "linger.ms": 5,
        "compression.type": "lz4",
    }
)

dlq = DlqPublisher(producer=_raw_producer, dlq_topic=settings.kafka_topic_dlq)
producer = PredictionEventProducer(settings=settings, serde=serde)
registry = ModelRegistry(settings=settings)
extractor = FeatureExtractor()
engine = InferenceEngine(registry=registry, extractor=extractor)
cb_manager = CircuitBreakerManager(settings=settings)

consumer = PredictionConsumer(
    settings=settings,
    serde=serde,
    engine=engine,
    producer=producer,
    cache=cache,
    dlq=dlq,
)

# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI):  # noqa: ANN001
    """Manage application startup and graceful shutdown."""
    # ------------------------------------------------------------------
    # Startup
    # ------------------------------------------------------------------
    configure_logging(settings.log_level)
    logger.info("prediction_service_starting", version="1.0.0", port=settings.port)

    if settings.vault_enabled:
        logger.info("vault_authenticating")
        vault_client.authenticate()

    engine.load()
    cache.connect()
    consumer.start()

    # Register health checks
    health_module.register_health_check("consumer", consumer.is_healthy)
    health_module.register_health_check("producer", producer.is_healthy)
    health_module.register_health_check("vault", vault_client.is_healthy)
    health_module.register_health_check("model", engine.is_healthy)
    health_module.register_health_check("redis", cache.is_healthy)

    # Inject engine into admin module
    admin_module.set_engine(engine)

    # Start background health-refresh loop
    _refresh_task = asyncio.create_task(health_module.start_health_refresh_loop())

    # Schedule consumer-lag metric update every 30 s
    _lag_task = asyncio.create_task(_lag_update_loop())

    logger.info("prediction_service_started")

    yield

    # ------------------------------------------------------------------
    # Shutdown
    # ------------------------------------------------------------------
    logger.info("prediction_service_stopping")
    _refresh_task.cancel()
    _lag_task.cancel()

    consumer.stop()
    producer.flush()
    cache.close()
    vault_client.close()

    logger.info("prediction_service_stopped")


async def _lag_update_loop() -> None:
    """Periodically update circuit breaker metrics every 30 seconds."""
    while True:
        try:
            cb_manager.update_metrics()
        except Exception:  # noqa: BLE001
            pass
        await asyncio.sleep(30)


# ---------------------------------------------------------------------------
# FastAPI application
# ---------------------------------------------------------------------------

app = FastAPI(
    title="Prediction Service",
    description=(
        "Consumes ScheduleComputedEvents from Kafka, runs ML inference to predict "
        "train delays, and publishes DelayPredictionEvents."
    ),
    version="1.0.0",
    lifespan=lifespan,
)

app.include_router(health_router)
app.include_router(metrics_router)
app.include_router(admin_router)


# ---------------------------------------------------------------------------
# SIGTERM handler
# ---------------------------------------------------------------------------


def _handle_sigterm(signum: int, frame: object) -> None:  # noqa: ANN001
    logger.info("sigterm_received", message="Initiating graceful shutdown")
    consumer.stop()
    sys.exit(0)


signal.signal(signal.SIGTERM, _handle_sigterm)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    uvicorn.run(
        "prediction_service.main:app",
        host="0.0.0.0",
        port=settings.port,
        log_config=None,  # structlog handles logging
        access_log=False,
    )
