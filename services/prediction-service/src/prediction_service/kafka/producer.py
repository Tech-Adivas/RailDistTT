"""Kafka producer for DelayPredictionEvent Avro messages.

Publishes predictions to ``railway.delay.prediction.computed`` with retry logic
and Prometheus instrumentation.
"""

from __future__ import annotations

import time
import uuid
from datetime import datetime, timezone
from typing import TYPE_CHECKING

import confluent_kafka
from confluent_kafka.schema_registry.avro import AvroSerializer
from confluent_kafka.serialization import MessageField, SerializationContext

from prediction_service.inference.engine import DelayPrediction
from prediction_service.kafka.avro_serde import AvroSerde
from prediction_service.observability.logging import get_logger
from prediction_service.observability.metrics import (
    KAFKA_PUBLISH_DURATION,
    KAFKA_PUBLISHED_TOTAL,
)

if TYPE_CHECKING:
    from prediction_service.config import Settings

logger = get_logger(__name__)

# ---------------------------------------------------------------------------
# Avro schema for the outbound DelayPredictionEvent
# ---------------------------------------------------------------------------

PREDICTION_EVENT_SCHEMA = """{
  "namespace": "com.railway.platform.events",
  "name": "DelayPredictionEvent",
  "type": "record",
  "fields": [
    {"name": "metadata", "type": {
      "namespace": "com.railway.platform.events",
      "name": "PredictionEventMetadata",
      "type": "record",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "eventType", "type": "string"},
        {"name": "occurredAt", "type": {"type": "long", "logicalType": "timestamp-millis"}},
        {"name": "correlationId", "type": "string"},
        {"name": "actor", "type": ["null", "string"], "default": null},
        {"name": "schemaVersion", "type": "int", "default": 1}
      ]
    }},
    {"name": "routeId", "type": "string"},
    {"name": "trainId", "type": ["null", "string"], "default": null},
    {"name": "predictedDelayMinutes", "type": "int"},
    {"name": "confidenceScore", "type": "float"},
    {"name": "modelVersion", "type": "string"},
    {"name": "triggeringScheduleEventId", "type": "string"}
  ]
}"""

_RETRY_DELAYS = [1.0, 2.0, 4.0]  # seconds between retries


class PredictionEventProducer:
    """Builds and publishes DelayPredictionEvent messages to Kafka."""

    def __init__(self, settings: "Settings", serde: AvroSerde) -> None:
        self._settings = settings
        self._serde = serde
        self._topic = settings.kafka_topic_prediction
        self._producer = self._build_producer()
        self._serializer: AvroSerializer = serde.get_serializer(PREDICTION_EVENT_SCHEMA)
        logger.info("prediction_producer_initialized", topic=self._topic)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def publish(self, prediction: DelayPrediction, correlation_id: str) -> None:
        """Serialize and publish a :class:`DelayPrediction` as a Kafka Avro message.

        Retries up to 3 times with 1 s / 2 s / 4 s back-off on transient errors.
        Propagates ``correlation_id`` as the Kafka header ``X-Correlation-Id``.
        """
        payload = _build_payload(prediction, correlation_id)
        headers = [("X-Correlation-Id", correlation_id.encode())]

        last_exc: Exception | None = None
        start = time.monotonic()

        for attempt, retry_delay in enumerate([0.0] + _RETRY_DELAYS):
            if attempt > 0:
                logger.warning(
                    "kafka_publish_retry",
                    topic=self._topic,
                    attempt=attempt,
                    delay_seconds=retry_delay,
                    error=str(last_exc),
                )
                time.sleep(retry_delay)

            try:
                serialized = self._serializer(
                    payload,
                    SerializationContext(self._topic, MessageField.VALUE),
                )
                self._producer.produce(
                    topic=self._topic,
                    value=serialized,
                    headers=headers,
                    on_delivery=_delivery_callback,
                )
                self._producer.poll(0)

                elapsed = time.monotonic() - start
                KAFKA_PUBLISH_DURATION.observe(elapsed)
                KAFKA_PUBLISHED_TOTAL.labels(topic=self._topic, status="success").inc()
                logger.info(
                    "prediction_published",
                    topic=self._topic,
                    route_id=prediction.route_id,
                    model_version=prediction.model_version,
                    correlation_id=correlation_id,
                )
                return

            except Exception as exc:  # noqa: BLE001
                last_exc = exc

        # All retries exhausted
        elapsed = time.monotonic() - start
        KAFKA_PUBLISH_DURATION.observe(elapsed)
        KAFKA_PUBLISHED_TOTAL.labels(topic=self._topic, status="error").inc()
        logger.error(
            "kafka_publish_failed",
            topic=self._topic,
            route_id=prediction.route_id,
            error=str(last_exc),
            correlation_id=correlation_id,
        )
        raise RuntimeError(
            f"Failed to publish prediction after {len(_RETRY_DELAYS) + 1} attempts"
        ) from last_exc

    def flush(self, timeout: float = 10.0) -> None:
        """Flush any outstanding messages within *timeout* seconds."""
        remaining = self._producer.flush(timeout)
        if remaining:
            logger.warning("kafka_flush_incomplete", remaining_messages=remaining)

    def is_healthy(self) -> bool:
        """Return True if the producer appears operational."""
        try:
            # list_topics with short timeout is a lightweight liveness probe
            metadata = self._producer.list_topics(timeout=3)
            return metadata is not None
        except Exception:  # noqa: BLE001
            return False

    def close(self) -> None:
        """Flush and close the underlying Kafka producer."""
        self.flush()
        logger.info("prediction_producer_closed")

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _build_producer(self) -> confluent_kafka.Producer:
        conf: dict = {
            "bootstrap.servers": self._settings.kafka_bootstrap_servers,
            "acks": "all",
            "retries": 0,  # we handle retries ourselves
            "enable.idempotence": True,
            "compression.type": "lz4",
            "linger.ms": 5,
        }
        _apply_sasl(conf, self._settings)
        return confluent_kafka.Producer(conf)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_payload(prediction: DelayPrediction, correlation_id: str) -> dict:
    """Build the Avro-compatible dict for a DelayPredictionEvent."""
    now_ms = int(datetime.now(tz=timezone.utc).timestamp() * 1000)
    return {
        "metadata": {
            "eventId": str(uuid.uuid4()),
            "eventType": "DelayPredictionEvent",
            "occurredAt": now_ms,
            "correlationId": correlation_id,
            "actor": None,
            "schemaVersion": 1,
        },
        "routeId": prediction.route_id,
        "trainId": prediction.train_id,
        "predictedDelayMinutes": prediction.predicted_delay_minutes,
        "confidenceScore": prediction.confidence_score,
        "modelVersion": prediction.model_version,
        "triggeringScheduleEventId": prediction.triggering_schedule_event_id,
    }


def _delivery_callback(err: confluent_kafka.KafkaError | None, msg: confluent_kafka.Message) -> None:
    if err:
        logger.error(
            "kafka_delivery_failed",
            topic=msg.topic(),
            partition=msg.partition(),
            error=str(err),
        )
    else:
        logger.debug(
            "kafka_delivery_confirmed",
            topic=msg.topic(),
            partition=msg.partition(),
            offset=msg.offset(),
        )


def _apply_sasl(conf: dict, settings: "Settings") -> None:
    """Conditionally add SASL/SCRAM config when a username is set."""
    username = settings.kafka_sasl_username
    if not username:
        return
    password = settings.kafka_sasl_password.get_secret_value()
    conf.update(
        {
            "security.protocol": "SASL_SSL",
            "sasl.mechanism": "SCRAM-SHA-512",
            "sasl.username": username,
            "sasl.password": password,
        }
    )
