"""Kafka consumer loop for ScheduleComputedEvent messages.

Consumes from ``railway.schedule.computed``, runs inference via
:class:`InferenceEngine`, and publishes to ``railway.delay.prediction.computed``.
Unprocessable messages are sent to the DLQ.
Manual offset commit after every processed message (no auto-commit).
"""

from __future__ import annotations

import threading
import time
import uuid
from typing import TYPE_CHECKING

import confluent_kafka
from confluent_kafka.serialization import MessageField, SerializationContext

from prediction_service.idempotency.cache import ProcessedEventCache
from prediction_service.inference.engine import InferenceEngine
from prediction_service.kafka.avro_serde import AvroSerde
from prediction_service.kafka.dlq import DlqPublisher
from prediction_service.kafka.producer import PredictionEventProducer
from prediction_service.observability.logging import (
    bind_correlation_id,
    clear_correlation_id,
    get_logger,
)
from prediction_service.observability.metrics import (
    CONSUMER_LAG,
    KAFKA_CONSUMED_TOTAL,
    PREDICTIONS_TOTAL,
    PREDICTION_ERRORS_TOTAL,
)

if TYPE_CHECKING:
    from prediction_service.config import Settings

logger = get_logger(__name__)

# ---------------------------------------------------------------------------
# Inline ScheduleComputedEvent Avro schema
# Matches shared/events/src/main/avro/schedule-computed-event.avsc exactly.
# ---------------------------------------------------------------------------

SCHEDULE_EVENT_SCHEMA = """{
  "namespace": "com.railway.platform.events",
  "name": "ScheduleComputedEvent",
  "type": "record",
  "fields": [
    {"name": "metadata", "type": {
      "namespace": "com.railway.platform.events",
      "name": "EventMetadata",
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
    {"name": "timetableId", "type": "string"},
    {"name": "lineId", "type": "string"},
    {"name": "effectiveDate", "type": {"type": "int", "logicalType": "date"}},
    {"name": "expiryDate", "type": ["null", {"type": "int", "logicalType": "date"}], "default": null},
    {"name": "services", "type": {"type": "array", "items": {
      "name": "ScheduledService", "type": "record",
      "fields": [
        {"name": "serviceId", "type": "string"},
        {"name": "trainNumber", "type": "string"},
        {"name": "stops", "type": {"type": "array", "items": {
          "name": "StopTime", "type": "record",
          "fields": [
            {"name": "stationCode", "type": "string"},
            {"name": "stationName", "type": "string"},
            {"name": "arrivalTime", "type": ["null", {"type": "int", "logicalType": "time-millis"}], "default": null},
            {"name": "departureTime", "type": ["null", {"type": "int", "logicalType": "time-millis"}], "default": null},
            {"name": "platform", "type": ["null", "string"], "default": null}
          ]
        }}},
        {"name": "affectedByMaintenance", "type": "boolean", "default": false}
      ]
    }}},
    {"name": "triggeringEventId", "type": "string"}
  ]
}"""

_POLL_TIMEOUT_SECONDS = 1.0
_STOP_WAIT_IN_FLIGHT_SECONDS = 10


class PredictionConsumer:
    """Background Kafka consumer that drives the prediction pipeline."""

    def __init__(
        self,
        settings: "Settings",
        serde: AvroSerde,
        engine: InferenceEngine,
        producer: PredictionEventProducer,
        cache: ProcessedEventCache,
        dlq: DlqPublisher,
    ) -> None:
        self._settings = settings
        self._serde = serde
        self._engine = engine
        self._producer = producer
        self._cache = cache
        self._dlq = dlq

        self._consumer: confluent_kafka.Consumer | None = None
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._in_flight = 0
        self._in_flight_lock = threading.Lock()
        self._connected = False

        self._deserializer = serde.get_deserializer(SCHEDULE_EVENT_SCHEMA)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Start the consumer poll loop in a daemon background thread."""
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run,
            name="prediction-consumer",
            daemon=True,
        )
        self._thread.start()
        logger.info(
            "consumer_started",
            group=self._settings.kafka_consumer_group,
            topic=self._settings.kafka_topic_schedule,
        )

    def stop(self) -> None:
        """Signal graceful shutdown.

        1. Signal the poll loop to stop.
        2. Wait up to 10 s for in-flight messages to finish.
        3. Commit offsets and close the consumer.
        """
        logger.info("consumer_stop_requested")
        self._stop_event.set()

        if self._thread and self._thread.is_alive():
            # Pause polling while waiting for in-flight
            if self._consumer:
                try:
                    assignment = self._consumer.assignment()
                    if assignment:
                        self._consumer.pause(assignment)
                except Exception:  # noqa: BLE001
                    pass

            deadline = time.monotonic() + _STOP_WAIT_IN_FLIGHT_SECONDS
            while time.monotonic() < deadline:
                with self._in_flight_lock:
                    if self._in_flight == 0:
                        break
                time.sleep(0.1)
            else:
                logger.warning("consumer_stop_inflight_timeout")

            self._thread.join(timeout=15)

        if self._consumer:
            try:
                self._consumer.commit(asynchronous=False)
            except Exception:  # noqa: BLE001
                pass
            try:
                self._consumer.close()
            except Exception:  # noqa: BLE001
                pass
            self._consumer = None
            self._connected = False

        logger.info("consumer_stopped")

    def is_healthy(self) -> bool:
        """Return True if the consumer thread is alive and connected to Kafka."""
        return (
            self._thread is not None
            and self._thread.is_alive()
            and self._connected
        )

    # ------------------------------------------------------------------
    # Main poll loop
    # ------------------------------------------------------------------

    def _run(self) -> None:
        """Poll loop — runs until _stop_event is set."""
        try:
            self._consumer = self._build_consumer()
            self._consumer.subscribe([self._settings.kafka_topic_schedule])
            self._connected = True
            logger.info(
                "consumer_subscribed",
                topic=self._settings.kafka_topic_schedule,
            )

            while not self._stop_event.is_set():
                msg = self._consumer.poll(timeout=_POLL_TIMEOUT_SECONDS)
                if msg is None:
                    continue
                if msg.error():
                    if msg.error().code() == confluent_kafka.KafkaError._PARTITION_EOF:
                        continue
                    logger.error("consumer_poll_error", error=str(msg.error()))
                    PREDICTION_ERRORS_TOTAL.labels(error_type="poll_error").inc()
                    continue

                with self._in_flight_lock:
                    self._in_flight += 1
                try:
                    self._process_message(msg)
                finally:
                    with self._in_flight_lock:
                        self._in_flight -= 1

        except Exception as exc:  # noqa: BLE001
            logger.error("consumer_loop_crashed", error=str(exc), exc_info=True)
            self._connected = False
        finally:
            self._connected = False

    def _process_message(self, msg: confluent_kafka.Message) -> None:
        """Deserialize → deduplicate → infer → publish → commit."""
        topic = msg.topic()
        partition = msg.partition()
        offset = msg.offset()
        raw_value: bytes = msg.value()

        # Extract or generate correlation ID from Kafka headers
        correlation_id = _extract_correlation_id(msg)
        bind_correlation_id(correlation_id)

        try:
            KAFKA_CONSUMED_TOTAL.labels(topic=topic).inc()

            # Update consumer lag metric
            _update_lag_metric(msg)

            # -----------------------------------------------------------------
            # Deserialise
            # -----------------------------------------------------------------
            try:
                event: dict = self._deserializer(
                    raw_value,
                    SerializationContext(topic, MessageField.VALUE),
                )
            except Exception as exc:
                logger.error(
                    "deserialization_failed",
                    topic=topic,
                    partition=partition,
                    offset=offset,
                    error=str(exc),
                    correlation_id=correlation_id,
                )
                self._dlq.publish(
                    original_topic=topic,
                    original_partition=partition,
                    original_offset=offset,
                    raw_value=raw_value,
                    error_message=f"Deserialization failed: {exc}",
                    correlation_id=correlation_id,
                )
                self._consumer.commit(message=msg, asynchronous=False)  # type: ignore[union-attr]
                return

            # -----------------------------------------------------------------
            # Idempotency check
            # -----------------------------------------------------------------
            event_id: str = (event.get("metadata") or {}).get("eventId", "")
            if not event_id:
                event_id = f"no-id-{uuid.uuid4()}"

            if self._cache.is_duplicate(event_id):
                logger.debug("duplicate_event_skipped", event_id=event_id, correlation_id=correlation_id)
                self._consumer.commit(message=msg, asynchronous=False)  # type: ignore[union-attr]
                return

            # -----------------------------------------------------------------
            # Inference
            # -----------------------------------------------------------------
            prediction = self._engine.predict(event)

            # -----------------------------------------------------------------
            # Publish
            # -----------------------------------------------------------------
            self._producer.publish(prediction, correlation_id)

            # -----------------------------------------------------------------
            # Mark processed and commit
            # -----------------------------------------------------------------
            self._cache.mark_processed(event_id)
            self._consumer.commit(message=msg, asynchronous=False)  # type: ignore[union-attr]

            PREDICTIONS_TOTAL.labels(
                model_version=prediction.model_version,
                status="success",
            ).inc()
            logger.info(
                "message_processed",
                event_id=event_id,
                route_id=prediction.route_id,
                predicted_delay=prediction.predicted_delay_minutes,
                correlation_id=correlation_id,
            )

        except Exception as exc:  # noqa: BLE001
            logger.error(
                "message_processing_failed",
                topic=topic,
                partition=partition,
                offset=offset,
                error=str(exc),
                correlation_id=correlation_id,
                exc_info=True,
            )
            PREDICTION_ERRORS_TOTAL.labels(error_type="processing_error").inc()
            PREDICTIONS_TOTAL.labels(model_version="unknown", status="error").inc()
            # Still commit to avoid re-processing loop; publish to DLQ
            self._dlq.publish(
                original_topic=topic,
                original_partition=partition,
                original_offset=offset,
                raw_value=raw_value,
                error_message=str(exc),
                correlation_id=correlation_id,
            )
            if self._consumer:
                self._consumer.commit(message=msg, asynchronous=False)

        finally:
            clear_correlation_id()

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _build_consumer(self) -> confluent_kafka.Consumer:
        conf: dict = {
            "bootstrap.servers": self._settings.kafka_bootstrap_servers,
            "group.id": self._settings.kafka_consumer_group,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
            "isolation.level": "read_committed",
            "fetch.min.bytes": 1,
            "session.timeout.ms": 30_000,
            "heartbeat.interval.ms": 3_000,
            "max.poll.interval.ms": 300_000,
        }
        _apply_sasl_consumer(conf, self._settings)
        return confluent_kafka.Consumer(conf)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _extract_correlation_id(msg: confluent_kafka.Message) -> str:
    headers = msg.headers() or []
    for key, value in headers:
        if key == "X-Correlation-Id" and value:
            try:
                return value.decode("utf-8")
            except (UnicodeDecodeError, AttributeError):
                pass
    return str(uuid.uuid4())


def _update_lag_metric(msg: confluent_kafka.Message) -> None:
    """Optimistic lag metric — uses message timestamp offset as a proxy."""
    try:
        CONSUMER_LAG.labels(
            topic=msg.topic(),
            partition=str(msg.partition()),
        ).set(0)  # A real lag calculation requires AdminClient; use 0 as placeholder
    except Exception:  # noqa: BLE001
        pass


def _apply_sasl_consumer(conf: dict, settings: "Settings") -> None:
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
