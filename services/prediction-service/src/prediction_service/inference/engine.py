"""Inference engine — orchestrates feature extraction and ML model scoring.

Thread-safe model hot-reload via RLock.
Records INFERENCE_DURATION histogram and updates MODEL_VERSION_INFO gauge.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import TYPE_CHECKING, Optional

from prediction_service.inference.feature_extractor import FeatureExtractor, FeatureVector
from prediction_service.inference.model_registry import ModelArtifact, ModelRegistry
from prediction_service.observability.logging import get_logger
from prediction_service.observability.metrics import INFERENCE_DURATION, MODEL_VERSION_INFO

if TYPE_CHECKING:
    pass  # avoid circular imports

logger = get_logger(__name__)

_FALLBACK_VERSION = "unknown"


@dataclass
class DelayPrediction:
    """The output of a single inference run."""

    route_id: str
    train_id: Optional[str]
    predicted_delay_minutes: int
    confidence_score: float
    model_version: str
    triggering_schedule_event_id: str


class InferenceEngine:
    """Orchestrates feature extraction and model inference."""

    def __init__(self, registry: ModelRegistry, extractor: FeatureExtractor) -> None:
        self._registry = registry
        self._extractor = extractor
        self._artifact: ModelArtifact | None = None
        self._lock = threading.RLock()

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def load(self) -> None:
        """Load the model from the registry.  Must be called at startup."""
        artifact = self._registry.load_production_model()
        with self._lock:
            self._artifact = artifact
        _set_version_gauge(artifact.version)
        logger.info(
            "model_loaded",
            version=artifact.version,
            framework=artifact.framework,
        )

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    def predict(self, event: dict) -> DelayPrediction:
        """Run inference on a deserialized ScheduleComputedEvent dict.

        Records INFERENCE_DURATION. Returns a fallback prediction (confidence=0.0)
        on any exception so the consumer can still publish a result.
        """
        with self._lock:
            artifact = self._artifact

        version = artifact.version if artifact is not None else _FALLBACK_VERSION

        # Identify primary service / route info for the output event
        route_id: str = event.get("lineId", "unknown")
        services: list = event.get("services", []) or []
        train_id: Optional[str] = services[0].get("trainNumber") if services else None
        triggering_event_id: str = event.get("triggeringEventId", "")

        try:
            with INFERENCE_DURATION.labels(model_version=version).time():
                fv: FeatureVector = self._extractor.extract(event)
                X = self._extractor.to_numpy(fv)

                if artifact is None:
                    raise RuntimeError("No model loaded")

                delay_raw = float(artifact.model.predict(X)[0])
                delay_minutes = max(0, int(round(delay_raw)))

                # Confidence from predict_proba if available
                proba = artifact.model.predict_proba(X)
                confidence = float(proba[0, 1]) if proba.shape[1] >= 2 else 0.5  # type: ignore[index]
                confidence = max(0.0, min(1.0, confidence))

        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "inference_failed",
                error=str(exc),
                route_id=route_id,
                model_version=version,
                message="Returning fallback prediction with confidence=0.0",
            )
            return DelayPrediction(
                route_id=route_id,
                train_id=train_id,
                predicted_delay_minutes=0,
                confidence_score=0.0,
                model_version=version,
                triggering_schedule_event_id=triggering_event_id,
            )

        return DelayPrediction(
            route_id=route_id,
            train_id=train_id,
            predicted_delay_minutes=delay_minutes,
            confidence_score=confidence,
            model_version=version,
            triggering_schedule_event_id=triggering_event_id,
        )

    # ------------------------------------------------------------------
    # Hot-reload
    # ------------------------------------------------------------------

    def reload(self) -> str:
        """Hot-reload the latest Production model without restarting the service.

        Returns:
            The new model version string.
        """
        logger.info("model_reload_requested")
        new_artifact = self._registry.load_production_model()
        with self._lock:
            self._artifact = new_artifact
        _set_version_gauge(new_artifact.version)
        logger.info("model_reloaded", new_version=new_artifact.version)
        return new_artifact.version

    # ------------------------------------------------------------------
    # Introspection
    # ------------------------------------------------------------------

    def get_version(self) -> str:
        """Return the currently loaded model version string."""
        with self._lock:
            return self._artifact.version if self._artifact is not None else _FALLBACK_VERSION

    def is_healthy(self) -> bool:
        """Return True if a model is loaded."""
        with self._lock:
            return self._artifact is not None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _set_version_gauge(version: str) -> None:
    """Update the MODEL_VERSION_INFO gauge for the given version label."""
    # Clear previous version label(s) is not strictly possible with prometheus_client
    # without restarting, so we just set the new one.
    MODEL_VERSION_INFO.labels(version=version).set(1)
