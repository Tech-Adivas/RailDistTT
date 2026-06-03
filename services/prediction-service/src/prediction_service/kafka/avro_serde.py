"""Thin wrapper around confluent_kafka's Avro serializers / deserializers.

Caches serializer/deserializer instances by schema string to avoid repeated
Schema Registry round-trips.
"""

from __future__ import annotations

from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroDeserializer, AvroSerializer

from prediction_service.observability.logging import get_logger

logger = get_logger(__name__)


class AvroSerde:
    """Factory for Avro serializers and deserializers backed by a Schema Registry."""

    def __init__(self, schema_registry_url: str) -> None:
        self._registry_client = SchemaRegistryClient({"url": schema_registry_url})
        self._deserializers: dict[str, AvroDeserializer] = {}
        self._serializers: dict[str, AvroSerializer] = {}
        logger.info("avro_serde_initialized", schema_registry_url=schema_registry_url)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_deserializer(self, schema_str: str) -> AvroDeserializer:
        """Return (creating if needed) an :class:`AvroDeserializer` for *schema_str*."""
        if schema_str not in self._deserializers:
            self._deserializers[schema_str] = AvroDeserializer(
                self._registry_client,
                schema_str,
            )
        return self._deserializers[schema_str]

    def get_serializer(self, schema_str: str) -> AvroSerializer:
        """Return (creating if needed) an :class:`AvroSerializer` for *schema_str*."""
        if schema_str not in self._serializers:
            self._serializers[schema_str] = AvroSerializer(
                self._registry_client,
                schema_str,
            )
        return self._serializers[schema_str]
