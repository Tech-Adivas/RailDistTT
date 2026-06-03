"""Unit tests for InferenceEngine."""

from __future__ import annotations

import datetime
from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from prediction_service.inference.engine import DelayPrediction, InferenceEngine
from prediction_service.inference.feature_extractor import FeatureExtractor
from prediction_service.inference.model_registry import ModelArtifact, ModelRegistry

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_AVRO_EPOCH_OFFSET = 719163


def _date_to_avro_days(d: datetime.date) -> int:
    return d.toordinal() - _AVRO_EPOCH_OFFSET


def _make_event(line_id: str = "LINE-1") -> dict:
    return {
        "metadata": {
            "eventId": "evt-001",
            "eventType": "ScheduleComputedEvent",
            "occurredAt": 1_700_000_000_000,
            "correlationId": "corr-001",
            "actor": None,
            "schemaVersion": 1,
        },
        "timetableId": "tt-001",
        "lineId": line_id,
        "effectiveDate": _date_to_avro_days(datetime.date(2026, 6, 3)),
        "expiryDate": None,
        "services": [
            {
                "serviceId": "svc-1",
                "trainNumber": "T001",
                "stops": [
                    {
                        "stationCode": "A",
                        "stationName": "Alpha",
                        "arrivalTime": None,
                        "departureTime": 8 * 60 * 60 * 1000,
                        "platform": "1",
                    },
                    {
                        "stationCode": "B",
                        "stationName": "Beta",
                        "arrivalTime": 10 * 60 * 60 * 1000,
                        "departureTime": None,
                        "platform": None,
                    },
                ],
                "affectedByMaintenance": False,
            }
        ],
        "triggeringEventId": "trigger-001",
    }


def _make_mock_artifact(delay: float = 5.0, confidence: float = 0.8) -> ModelArtifact:
    model = MagicMock()
    model.predict.return_value = np.array([delay])
    model.predict_proba.return_value = np.array([[1.0 - confidence, confidence]])
    return ModelArtifact(model=model, version="test-v1", framework="mock")


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestInferenceEngine:
    def _make_engine(self, artifact: ModelArtifact | None = None) -> InferenceEngine:
        if artifact is None:
            artifact = _make_mock_artifact()
        registry = MagicMock(spec=ModelRegistry)
        registry.load_production_model.return_value = artifact
        extractor = FeatureExtractor()
        engine = InferenceEngine(registry=registry, extractor=extractor)
        engine.load()
        return engine

    def test_predict_returns_valid_prediction(self) -> None:
        engine = self._make_engine(_make_mock_artifact(delay=7.0, confidence=0.9))
        event = _make_event()
        prediction = engine.predict(event)

        assert isinstance(prediction, DelayPrediction)
        assert prediction.predicted_delay_minutes == 7
        assert prediction.confidence_score == pytest.approx(0.9, abs=1e-5)
        assert prediction.model_version == "test-v1"
        assert prediction.route_id == "LINE-1"
        assert prediction.train_id == "T001"
        assert prediction.triggering_schedule_event_id == "trigger-001"

    def test_predict_clamps_negative_delay(self) -> None:
        """Negative model output should be clamped to 0."""
        engine = self._make_engine(_make_mock_artifact(delay=-5.0))
        prediction = engine.predict(_make_event())
        assert prediction.predicted_delay_minutes == 0

    def test_predict_clamps_confidence_to_unit_interval(self) -> None:
        """Confidence exceeding 1.0 or below 0.0 is clamped."""
        artifact = _make_mock_artifact(confidence=0.99)
        artifact.model.predict_proba.return_value = np.array([[0.0, 1.5]])  # > 1.0
        engine = self._make_engine(artifact)
        prediction = engine.predict(_make_event())
        assert 0.0 <= prediction.confidence_score <= 1.0

    def test_predict_fallback_on_exception(self) -> None:
        """Any exception during inference should return a fallback prediction (confidence=0.0)."""
        model = MagicMock()
        model.predict.side_effect = RuntimeError("model exploded")
        model.predict_proba.return_value = np.array([[0.5, 0.5]])
        artifact = ModelArtifact(model=model, version="v-err", framework="mock")
        engine = self._make_engine(artifact)

        prediction = engine.predict(_make_event())

        assert prediction.confidence_score == 0.0
        assert prediction.predicted_delay_minutes == 0
        assert prediction.model_version == "v-err"

    def test_reload_updates_version(self) -> None:
        engine = self._make_engine(_make_mock_artifact())
        assert engine.get_version() == "test-v1"

        new_artifact = _make_mock_artifact(delay=10.0)
        new_artifact = ModelArtifact(
            model=new_artifact.model, version="test-v2", framework="mock"
        )
        engine._registry.load_production_model.return_value = new_artifact  # type: ignore[attr-defined]

        new_version = engine.reload()

        assert new_version == "test-v2"
        assert engine.get_version() == "test-v2"

    def test_reload_uses_new_model_for_subsequent_predictions(self) -> None:
        engine = self._make_engine(_make_mock_artifact(delay=3.0))
        prediction_before = engine.predict(_make_event())
        assert prediction_before.predicted_delay_minutes == 3

        new_model = MagicMock()
        new_model.predict.return_value = np.array([15.0])
        new_model.predict_proba.return_value = np.array([[0.2, 0.8]])
        new_artifact = ModelArtifact(model=new_model, version="v2", framework="mock")
        engine._registry.load_production_model.return_value = new_artifact  # type: ignore[attr-defined]
        engine.reload()

        prediction_after = engine.predict(_make_event())
        assert prediction_after.predicted_delay_minutes == 15

    def test_is_healthy_after_load(self) -> None:
        engine = self._make_engine()
        assert engine.is_healthy() is True

    def test_is_healthy_before_load(self) -> None:
        registry = MagicMock(spec=ModelRegistry)
        extractor = FeatureExtractor()
        engine = InferenceEngine(registry=registry, extractor=extractor)
        assert engine.is_healthy() is False

    def test_get_version_before_load_returns_unknown(self) -> None:
        registry = MagicMock(spec=ModelRegistry)
        extractor = FeatureExtractor()
        engine = InferenceEngine(registry=registry, extractor=extractor)
        assert engine.get_version() == "unknown"
