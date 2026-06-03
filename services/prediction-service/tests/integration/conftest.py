"""Integration test fixtures providing Testcontainers-managed Kafka and Redis.

These fixtures are slow (pull Docker images on first run) and require Docker.
They are only used by tests marked ``@pytest.mark.integration``.
"""

from __future__ import annotations

import time

import pytest


# ---------------------------------------------------------------------------
# Kafka
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def kafka_container():
    """Start a Kafka (KRaft) container for the test session."""
    pytest.importorskip("testcontainers")
    from testcontainers.kafka import KafkaContainer  # type: ignore[import]

    container = KafkaContainer(image="confluentinc/cp-kafka:7.6.0")
    container.start()
    yield container
    container.stop()


@pytest.fixture(scope="session")
def kafka_bootstrap_servers(kafka_container) -> str:
    """Return the bootstrap-servers string for the Testcontainers Kafka."""
    return kafka_container.get_bootstrap_server()


# ---------------------------------------------------------------------------
# Schema Registry
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def schema_registry_container(kafka_container):
    """Start a Confluent Schema Registry container connected to Kafka."""
    pytest.importorskip("testcontainers")
    from testcontainers.core.container import DockerContainer  # type: ignore[import]

    bootstrap = kafka_container.get_bootstrap_server()
    container = (
        DockerContainer("confluentinc/cp-schema-registry:7.6.0")
        .with_env("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
        .with_env("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", bootstrap)
        .with_env("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
        .with_exposed_ports(8081)
    )
    container.start()
    # Wait for Schema Registry to be ready
    _wait_for_schema_registry(container)
    yield container
    container.stop()


def _wait_for_schema_registry(container, max_wait: int = 30) -> None:
    import urllib.error
    import urllib.request

    port = container.get_exposed_port(8081)
    url = f"http://localhost:{port}/subjects"
    deadline = time.monotonic() + max_wait
    while time.monotonic() < deadline:
        try:
            urllib.request.urlopen(url, timeout=2)
            return
        except (urllib.error.URLError, ConnectionRefusedError):
            time.sleep(1)
    raise TimeoutError(f"Schema Registry did not start within {max_wait}s")


@pytest.fixture(scope="session")
def schema_registry_url(schema_registry_container) -> str:
    port = schema_registry_container.get_exposed_port(8081)
    return f"http://localhost:{port}"


# ---------------------------------------------------------------------------
# Redis
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def redis_container():
    """Start a Redis container for the test session."""
    pytest.importorskip("testcontainers")
    from testcontainers.redis import RedisContainer  # type: ignore[import]

    container = RedisContainer(image="redis:7-alpine")
    container.start()
    yield container
    container.stop()


@pytest.fixture(scope="session")
def redis_host(redis_container) -> str:
    return redis_container.get_container_host_ip()


@pytest.fixture(scope="session")
def redis_port(redis_container) -> int:
    return int(redis_container.get_exposed_port(6379))
