"""Integration test: publish a ScheduleComputedEvent, assert a DelayPredictionEvent appears.

Requires Docker (Testcontainers). Run with:
    pytest -m integration tests/integration/test_consumer_producer.py
"""

from __future__ import annotations

import datetime
import json
import time
import uuid
from typing import Generator

import pytest

pytestmark = pytest.mark.integration

_AVRO_EPOCH_OFFSET = 719163


def _date_to_avro_days(d: datetime.date) -> int:
    return d.toordinal() - _AVRO_EPOCH_OFFSET


def _make_schedule_event() -> dict:
    return {
        "metadata": {
            "eventId": str(uuid.uuid4()),
            "eventType": "ScheduleComputedEvent",
            "occurredAt": int(time.time() * 1000),
            "correlationId": str(uuid.uuid4()),
            "actor": None,
            "schemaVersion": 1,
        },
        "timetableId": "tt-integration-001",
        "lineId": "LINE-INT-42",
        "effectiveDate": _date_to_avro_days(datetime.date(2026, 6, 3)),
        "expiryDate": None,
        "services": [
            {
                "serviceId": "svc-int-1",
                "trainNumber": "T-INT-001",
                "stops": [
                    {
                        "stationCode": "LDN",
                        "stationName": "London",
                        "arrivalTime": None,
                        "departureTime": 8 * 60 * 60 * 1000,
                        "platform": "1",
                    },
                    {
                        "stationCode": "MAN",
                        "stationName": "Manchester",
                        "arrivalTime": 10 * 60 * 60 * 1000,
                        "departureTime": None,
                        "platform": None,
                    },
                ],
                "affectedByMaintenance": False,
            }
        ],
        "triggeringEventId": str(uuid.uuid4()),
    }


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

from prediction_service.kafka.consumer import SCHEDULE_EVENT_SCHEMA  # noqa: E402
from prediction_service.kafka.producer import PREDICTION_EVENT_SCHEMA  # noqa: E402


@pytest.fixture(scope="module")
def settings(kafka_bootstrap_servers: str, schema_registry_url: str):
    """Override-loaded Settings pointed at Testcontainers services."""
    from prediction_service.config import Settings

    return Settings(
        kafka_bootstrap_servers=kafka_bootstrap_servers,
        schema_registry_url=schema_registry_url,
        kafka_topic_schedule="railway.schedule.computed.test",
        kafka_topic_prediction="railway.delay.prediction.computed.test",
        kafka_topic_dlq="railway.delay.prediction.dlq.test",
        kafka_consumer_group=f"test-group-{uuid.uuid4().hex[:8]}",
        vault_enabled=False,
        mlflow_enabled=False,
        redis_host="localhost",
        redis_port=6379,
        log_level="WARNING",
    )


@pytest.fixture(scope="module")
def running_pipeline(settings):
    """Wire and start the full consumer→producer pipeline for this test module."""
    import confluent_kafka  # noqa: PLC0415

    from prediction_service.idempotency.cache import ProcessedEventCache  # noqa: PLC0415
    from prediction_service.inference.engine import InferenceEngine  # noqa: PLC0415
    from prediction_service.inference.feature_extractor import FeatureExtractor  # noqa: PLC0415
    from prediction_service.inference.model_registry import ModelRegistry  # noqa: PLC0415
    from prediction_service.kafka.avro_serde import AvroSerde  # noqa: PLC0415
    from prediction_service.kafka.consumer import PredictionConsumer  # noqa: PLC0415
    from prediction_service.kafka.dlq import DlqPublisher  # noqa: PLC0415
    from prediction_service.kafka.producer import PredictionEventProducer  # noqa: PLC0415

    serde = AvroSerde(settings.schema_registry_url)
    registry = ModelRegistry(settings)
    extractor = FeatureExtractor()
    engine = InferenceEngine(registry=registry, extractor=extractor)
    engine.load()

    cache = ProcessedEventCache(settings)
    # Skip Redis in integration tests (no Redis container for this fixture)
    # cache.connect()  # omit: will use in-memory fallback

    producer = PredictionEventProducer(settings=settings, serde=serde)

    raw_producer = confluent_kafka.Producer(
        {"bootstrap.servers": settings.kafka_bootstrap_servers}
    )
    dlq = DlqPublisher(producer=raw_producer, dlq_topic=settings.kafka_topic_dlq)

    consumer = PredictionConsumer(
        settings=settings,
        serde=serde,
        engine=engine,
        producer=producer,
        cache=cache,
        dlq=dlq,
    )
    consumer.start()

    yield {
        "settings": settings,
        "serde": serde,
        "consumer": consumer,
        "producer": producer,
    }

    consumer.stop()
    producer.flush()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_schedule_event_produces_prediction_event(running_pipeline: dict) -> None:
    """Publish a ScheduleComputedEvent; a DelayPredictionEvent must appear within 10 s."""
    import confluent_kafka  # noqa: PLC0415
    from confluent_kafka.serialization import MessageField, SerializationContext  # noqa: PLC0415

    settings = running_pipeline["settings"]
    serde = running_pipeline["serde"]

    # -----------------------------------------------------------------------
    # Produce a ScheduleComputedEvent to the input topic
    # -----------------------------------------------------------------------
    schedule_event = _make_schedule_event()
    correlation_id = schedule_event["metadata"]["correlationId"]

    serializer = serde.get_serializer(SCHEDULE_EVENT_SCHEMA)
    serialized = serializer(
        schedule_event,
        SerializationContext(settings.kafka_topic_schedule, MessageField.VALUE),
    )

    raw_producer = confluent_kafka.Producer(
        {"bootstrap.servers": settings.kafka_bootstrap_servers}
    )
    raw_producer.produce(
        topic=settings.kafka_topic_schedule,
        value=serialized,
        headers=[("X-Correlation-Id", correlation_id.encode())],
    )
    raw_producer.flush(timeout=10)

    # -----------------------------------------------------------------------
    # Poll the output topic for the prediction event (up to 10 s)
    # -----------------------------------------------------------------------
    consumer_conf = {
        "bootstrap.servers": settings.kafka_bootstrap_servers,
        "group.id": f"assert-group-{uuid.uuid4().hex[:8]}",
        "auto.offset.reset": "earliest",
        "enable.auto.commit": True,
    }
    output_consumer = confluent_kafka.Consumer(consumer_conf)
    output_consumer.subscribe([settings.kafka_topic_prediction])

    deserializer = serde.get_deserializer(PREDICTION_EVENT_SCHEMA)
    deadline = time.monotonic() + 10.0
    found_event: dict | None = None

    while time.monotonic() < deadline and found_event is None:
        msg = output_consumer.poll(timeout=1.0)
        if msg is None or msg.error():
            continue
        try:
            event = deserializer(
                msg.value(),
                SerializationContext(settings.kafka_topic_prediction, MessageField.VALUE),
            )
            found_event = event
        except Exception:  # noqa: BLE001
            pass

    output_consumer.close()

    assert found_event is not None, "No DelayPredictionEvent appeared within 10 seconds"
    assert found_event["routeId"] == "LINE-INT-42"
    assert isinstance(found_event["predictedDelayMinutes"], int)
    assert found_event["predictedDelayMinutes"] >= 0
    assert 0.0 <= found_event["confidenceScore"] <= 1.0
    assert found_event["triggeringScheduleEventId"] == schedule_event["triggeringEventId"]
