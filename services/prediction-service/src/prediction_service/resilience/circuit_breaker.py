"""Circuit breaker manager using pybreaker.

Manages named circuit breakers for external dependencies (Kafka, Redis, Vault, MLflow).
State changes are reflected in the CIRCUIT_BREAKER_STATE Prometheus gauge.
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from typing import TYPE_CHECKING, Any

import pybreaker

from prediction_service.observability.logging import get_logger
from prediction_service.observability.metrics import CIRCUIT_BREAKER_STATE

if TYPE_CHECKING:
    from prediction_service.config import Settings

logger = get_logger(__name__)

# Gauge integer values
_STATE_CLOSED = 0
_STATE_OPEN = 1
_STATE_HALF_OPEN = 2

_PYBREAKER_STATE_MAP: dict[str, int] = {
    "closed": _STATE_CLOSED,
    "open": _STATE_OPEN,
    "half-open": _STATE_HALF_OPEN,
}


class _MetricsListener(pybreaker.CircuitBreakerListener):
    """Update the CIRCUIT_BREAKER_STATE gauge on every state transition."""

    def __init__(self, dependency: str) -> None:
        self._dependency = dependency

    def state_change(self, cb: pybreaker.CircuitBreaker, old_state, new_state) -> None:  # noqa: ANN001
        state_int = _PYBREAKER_STATE_MAP.get(new_state.name, _STATE_CLOSED)
        CIRCUIT_BREAKER_STATE.labels(dependency=self._dependency).set(state_int)
        logger.info(
            "circuit_breaker_state_change",
            dependency=self._dependency,
            old_state=old_state.name,
            new_state=new_state.name,
        )

    def failure(self, cb: pybreaker.CircuitBreaker, exc: Exception) -> None:
        logger.warning(
            "circuit_breaker_failure",
            dependency=self._dependency,
            error=str(exc),
            fail_counter=cb.fail_counter,
        )

    def success(self, cb: pybreaker.CircuitBreaker) -> None:
        logger.debug("circuit_breaker_success", dependency=self._dependency)


class CircuitBreakerManager:
    """Factory and manager for named pybreaker circuit breakers."""

    def __init__(self, settings: "Settings") -> None:
        self._settings = settings
        self._breakers: dict[str, pybreaker.CircuitBreaker] = {}
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_breaker(self, dependency: str) -> pybreaker.CircuitBreaker:
        """Return (creating if needed) the circuit breaker for *dependency*."""
        with self._lock:
            if dependency not in self._breakers:
                listener = _MetricsListener(dependency)
                breaker = pybreaker.CircuitBreaker(
                    fail_max=self._settings.circuit_breaker_fail_max,
                    reset_timeout=self._settings.circuit_breaker_reset_timeout,
                    listeners=[listener],
                    name=dependency,
                )
                # Initialise the gauge in CLOSED state
                CIRCUIT_BREAKER_STATE.labels(dependency=dependency).set(_STATE_CLOSED)
                self._breakers[dependency] = breaker
                logger.info("circuit_breaker_created", dependency=dependency)
            return self._breakers[dependency]

    def call(
        self,
        dependency: str,
        fn: Callable[..., Any],
        *args: Any,
        fallback: Any = None,
        **kwargs: Any,
    ) -> Any:
        """Call *fn* through the circuit breaker for *dependency*.

        Returns *fallback* if the breaker is OPEN or if the call raises.
        """
        breaker = self.get_breaker(dependency)
        try:
            return breaker.call(fn, *args, **kwargs)
        except pybreaker.CircuitBreakerError:
            logger.warning("circuit_breaker_open", dependency=dependency, fallback=fallback)
            return fallback
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "circuit_breaker_call_failed",
                dependency=dependency,
                error=str(exc),
                fallback=fallback,
            )
            return fallback

    def get_state(self, dependency: str) -> int:
        """Return 0=CLOSED, 1=OPEN, 2=HALF_OPEN for *dependency*."""
        breaker = self.get_breaker(dependency)
        return _PYBREAKER_STATE_MAP.get(breaker.current_state, _STATE_CLOSED)

    def update_metrics(self) -> None:
        """Update CIRCUIT_BREAKER_STATE gauge for all known breakers."""
        with self._lock:
            for dependency, breaker in self._breakers.items():
                state_int = _PYBREAKER_STATE_MAP.get(breaker.current_state, _STATE_CLOSED)
                CIRCUIT_BREAKER_STATE.labels(dependency=dependency).set(state_int)
