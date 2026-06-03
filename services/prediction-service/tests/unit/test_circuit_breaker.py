"""Unit tests for CircuitBreakerManager."""

from __future__ import annotations

import time
from unittest.mock import MagicMock

import pybreaker
import pytest

from prediction_service.config import Settings
from prediction_service.resilience.circuit_breaker import (
    CircuitBreakerManager,
    _STATE_CLOSED,
    _STATE_HALF_OPEN,
    _STATE_OPEN,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture()
def settings() -> Settings:
    return Settings(
        kafka_bootstrap_servers="localhost:9092",
        circuit_breaker_fail_max=5,
        circuit_breaker_reset_timeout=1,  # short timeout for tests
    )


@pytest.fixture()
def manager(settings: Settings) -> CircuitBreakerManager:
    return CircuitBreakerManager(settings)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestCircuitBreakerManager:
    def test_breaker_created_in_closed_state(self, manager: CircuitBreakerManager) -> None:
        assert manager.get_state("kafka") == _STATE_CLOSED

    def test_breaker_opens_after_fail_max(self, manager: CircuitBreakerManager) -> None:
        """Circuit should open after 5 consecutive failures."""

        def _fail() -> None:
            raise RuntimeError("boom")

        for _ in range(5):
            manager.call("kafka", _fail, fallback=None)

        assert manager.get_state("kafka") == _STATE_OPEN

    def test_open_breaker_returns_fallback(self, manager: CircuitBreakerManager) -> None:
        """Once OPEN, calls should short-circuit and return the fallback immediately."""

        def _fail() -> None:
            raise RuntimeError("boom")

        for _ in range(5):
            manager.call("kafka", _fail, fallback="fallback-value")

        result = manager.call("kafka", lambda: "real-result", fallback="fallback-value")
        assert result == "fallback-value"

    def test_breaker_enters_half_open_after_reset_timeout(
        self, manager: CircuitBreakerManager
    ) -> None:
        """After reset_timeout seconds the breaker moves to HALF_OPEN."""

        def _fail() -> None:
            raise RuntimeError("boom")

        for _ in range(5):
            manager.call("kafka", _fail, fallback=None)

        assert manager.get_state("kafka") == _STATE_OPEN

        # Wait for reset timeout (set to 1 s in fixture)
        time.sleep(1.1)

        # HALF_OPEN: the next call is a probe — if it succeeds, breaker CLOSES
        manager.call("kafka", lambda: "ok", fallback=None)
        assert manager.get_state("kafka") == _STATE_CLOSED

    def test_successful_call_keeps_breaker_closed(self, manager: CircuitBreakerManager) -> None:
        result = manager.call("redis", lambda: 42, fallback=-1)
        assert result == 42
        assert manager.get_state("redis") == _STATE_CLOSED

    def test_separate_breakers_per_dependency(self, manager: CircuitBreakerManager) -> None:
        def _fail() -> None:
            raise RuntimeError("boom")

        for _ in range(5):
            manager.call("vault", _fail, fallback=None)

        assert manager.get_state("vault") == _STATE_OPEN
        assert manager.get_state("redis") == _STATE_CLOSED

    def test_update_metrics_does_not_raise(self, manager: CircuitBreakerManager) -> None:
        manager.get_breaker("kafka")
        manager.get_breaker("redis")
        manager.update_metrics()  # should not raise

    def test_get_breaker_returns_same_instance(self, manager: CircuitBreakerManager) -> None:
        b1 = manager.get_breaker("kafka")
        b2 = manager.get_breaker("kafka")
        assert b1 is b2

    def test_call_returns_fallback_on_exception_without_opening(
        self, manager: CircuitBreakerManager
    ) -> None:
        """First few failures should return fallback but not yet OPEN the breaker."""

        def _fail() -> None:
            raise RuntimeError("one failure")

        result = manager.call("redis", _fail, fallback="fb")
        assert result == "fb"
        # Breaker still CLOSED (only 1 failure out of fail_max=5)
        assert manager.get_state("redis") == _STATE_CLOSED
