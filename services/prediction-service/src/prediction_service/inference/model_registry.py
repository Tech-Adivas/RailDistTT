"""ML model registry — loads models from MLflow or returns a mock for local dev.

In production (mlflow_enabled=True): fetches the model tagged "Production" from
the configured MLflow tracking server.
In local dev (mlflow_enabled=False, default): returns a deterministic mock model
that requires no network or model artefacts.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any, NamedTuple

import numpy as np

from prediction_service.observability.logging import get_logger

if TYPE_CHECKING:
    from prediction_service.config import Settings

logger = get_logger(__name__)


# ---------------------------------------------------------------------------
# Public types
# ---------------------------------------------------------------------------


class ModelArtifact(NamedTuple):
    """A loaded model artefact together with its metadata."""

    model: Any  # sklearn Pipeline or mock; must have .predict() and .predict_proba()
    version: str  # e.g. "v1.0.0" or "mock-v1"
    framework: str  # "sklearn" | "mock"


# ---------------------------------------------------------------------------
# Mock model (default for local dev / CI)
# ---------------------------------------------------------------------------


class _MockModel:
    """Deterministic mock that produces plausible-looking outputs without any real ML.

    predict(X):         returns delay = int(X[0][0]) % 20 minutes
    predict_proba(X):   returns [[1-conf, conf]] where conf = 0.75
    """

    _CONFIDENCE = 0.75

    def predict(self, X: np.ndarray) -> np.ndarray:
        """Return delay_minutes for each row: line_id_hash % 20."""
        delays = (X[:, 0].astype(int) % 20).astype(float)
        return delays

    def predict_proba(self, X: np.ndarray) -> np.ndarray:
        """Return [[1-conf, conf]] for each row."""
        n = X.shape[0]
        conf = self._CONFIDENCE
        return np.full((n, 2), [1.0 - conf, conf])


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------


class ModelRegistry:
    """Load ML models from MLflow or return a mock for local dev."""

    def __init__(self, settings: "Settings") -> None:
        self._settings = settings

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def load_production_model(self) -> ModelArtifact:
        """Load the current Production model.

        Attempts to load from MLflow when ``mlflow_enabled=True``; falls back to
        the mock model on any failure (and always uses the mock when
        ``mlflow_enabled=False``).
        """
        if not self._settings.mlflow_enabled:
            logger.info(
                "mlflow_disabled",
                message="MLflow is disabled. Using mock model for local dev.",
            )
            return self._load_mock_model()

        return self._load_mlflow_model()

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _load_mlflow_model(self) -> ModelArtifact:
        """Attempt to load the Production model artefact from the MLflow registry."""
        try:
            import mlflow  # noqa: PLC0415
            import mlflow.sklearn  # noqa: PLC0415

            mlflow.set_tracking_uri(self._settings.mlflow_tracking_uri)
            model_uri = (
                f"models:/{self._settings.mlflow_model_name}"
                f"/{self._settings.mlflow_model_stage}"
            )
            logger.info("mlflow_loading_model", uri=model_uri)
            model = mlflow.sklearn.load_model(model_uri)

            # Attempt to read the registered version string
            client = mlflow.tracking.MlflowClient()
            versions = client.get_latest_versions(
                self._settings.mlflow_model_name,
                stages=[self._settings.mlflow_model_stage],
            )
            version_str = versions[0].version if versions else "unknown"

            logger.info("mlflow_model_loaded", version=version_str)
            return ModelArtifact(model=model, version=version_str, framework="sklearn")

        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "mlflow_load_failed",
                error=str(exc),
                message="Falling back to mock model",
            )
            return self._load_mock_model()

    def _load_mock_model(self) -> ModelArtifact:
        """Return the deterministic mock model (no external dependencies)."""
        logger.info("mock_model_loaded")
        return ModelArtifact(model=_MockModel(), version="mock-v1", framework="mock")
