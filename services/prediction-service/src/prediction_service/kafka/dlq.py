"""Dead-letter queue publisher for unprocessable Kafka messages.

Publishes the raw message bytes to the DLQ topic with diagnostic headers so that
ops staff can triage and replay poison messages.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import TYPE_CHECKING

import confluent_kafka

from prediction_service.observability.logging import get_logger
from prediction_service.observability.metrics import PREDICTION_ERRORS_TOTAL

if TYPE_CHECKING:
    pass

logger = get_logger(__name__)

_MAX_ERROR_HEADER_LEN = 500


class DlqPublisher:
    """Publish poison messages to a dead-letter topic with error context headers."""

    def __init__(self, producer: confluent_kafka.Producer, dlq_topic: str) -> None:
        self._producer = producer
        self._dlq_topic = dlq_topic

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def publish(
        self,
        original_topic: str,
        original_partition: int,
        original_offset: int,
        raw_value: bytes,
        error_message: str,
        correlation_id: str,
    ) -> None:
        """Publish *raw_value* to the DLQ topic with diagnostic headers.

        Headers:
            original-topic      — source topic
            original-partition  — source partition (decimal string)
            original-offset     — source offset (decimal string)
            error-message       — truncated error description
            error-timestamp     — ISO 8601 UTC
            correlation-id      — propagated correlation ID
        """
        truncated_error = error_message[:_MAX_ERROR_HEADER_LEN]
        timestamp = datetime.now(tz=timezone.utc).isoformat()

        headers = [
            ("original-topic", original_topic.encode()),
            ("original-partition", str(original_partition).encode()),
            ("original-offset", str(original_offset).encode()),
            ("error-message", truncated_error.encode()),
            ("error-timestamp", timestamp.encode()),
            ("correlation-id", correlation_id.encode()),
        ]

        try:
            self._producer.produce(
                topic=self._dlq_topic,
                value=raw_value,
                headers=headers,
            )
            self._producer.poll(0)
            logger.warning(
                "dlq_message_published",
                original_topic=original_topic,
                original_partition=original_partition,
                original_offset=original_offset,
                correlation_id=correlation_id,
                error=truncated_error,
            )
        except Exception as exc:  # noqa: BLE001
            logger.error(
                "dlq_publish_failed",
                error=str(exc),
                original_topic=original_topic,
                original_partition=original_partition,
                original_offset=original_offset,
            )

        PREDICTION_ERRORS_TOTAL.labels(error_type="poison_message").inc()
