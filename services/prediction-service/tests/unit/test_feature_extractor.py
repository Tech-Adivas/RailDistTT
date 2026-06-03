"""Unit tests for FeatureExtractor."""

from __future__ import annotations

import datetime

import numpy as np
import pytest

from prediction_service.inference.feature_extractor import FeatureExtractor, FeatureVector

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_AVRO_EPOCH_OFFSET = 719163  # days between 0001-01-01 and 1970-01-01 in proleptic Gregorian


def _date_to_avro_days(d: datetime.date) -> int:
    """Convert a Python date to Avro date (days since 1970-01-01)."""
    return d.toordinal() - _AVRO_EPOCH_OFFSET


def _make_event(
    line_id: str = "LINE-42",
    effective_date: datetime.date | None = None,
    services: list[dict] | None = None,
    triggering_event_id: str = "evt-001",
) -> dict:
    if effective_date is None:
        effective_date = datetime.date(2026, 6, 3)  # Tuesday

    return {
        "metadata": {
            "eventId": "sched-001",
            "eventType": "ScheduleComputedEvent",
            "occurredAt": 1_700_000_000_000,
            "correlationId": "corr-001",
            "actor": None,
            "schemaVersion": 1,
        },
        "timetableId": "tt-001",
        "lineId": line_id,
        "effectiveDate": _date_to_avro_days(effective_date),
        "expiryDate": None,
        "services": services if services is not None else [],
        "triggeringEventId": triggering_event_id,
    }


def _make_service(
    service_id: str = "svc-1",
    train_number: str = "T001",
    stops: list[dict] | None = None,
    affected_by_maintenance: bool = False,
) -> dict:
    if stops is None:
        stops = [
            {
                "stationCode": "LDN",
                "stationName": "London",
                "arrivalTime": None,
                "departureTime": 8 * 60 * 60 * 1000,  # 08:00 → 480 min
                "platform": "1",
            },
            {
                "stationCode": "MAN",
                "stationName": "Manchester",
                "arrivalTime": 10 * 60 * 60 * 1000,  # 10:00 → 600 min
                "departureTime": None,
                "platform": None,
            },
        ]
    return {
        "serviceId": service_id,
        "trainNumber": train_number,
        "stops": stops,
        "affectedByMaintenance": affected_by_maintenance,
    }


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestFeatureExtractor:
    def setup_method(self) -> None:
        self.extractor = FeatureExtractor()

    def test_extract_basic_fields(self) -> None:
        """Basic extraction from a well-formed event."""
        event = _make_event(
            line_id="LINE-42",
            effective_date=datetime.date(2026, 6, 3),  # Wednesday
            services=[_make_service()],
        )
        fv = self.extractor.extract(event)

        assert isinstance(fv, FeatureVector)
        assert fv.line_id_hash == abs(hash("LINE-42")) % 1000
        # 2026-06-03 is a Wednesday → weekday 2
        assert fv.day_of_week == 2
        assert fv.month == 6
        assert fv.service_count == 1

    def test_departure_arrival_times(self) -> None:
        """Departure / arrival times are converted correctly from time-millis."""
        event = _make_event(services=[_make_service()])
        fv = self.extractor.extract(event)

        # departure at 08:00 → 480 min; arrival at 10:00 → 600 min
        assert fv.earliest_departure_min == 480
        assert fv.latest_arrival_min == 600

    def test_avg_stops(self) -> None:
        svc1 = _make_service(service_id="s1", stops=[
            {"stationCode": "A", "stationName": "A", "arrivalTime": None,
             "departureTime": 1000, "platform": None},
            {"stationCode": "B", "stationName": "B", "arrivalTime": 2000,
             "departureTime": None, "platform": None},
        ])
        svc2 = _make_service(service_id="s2", stops=[
            {"stationCode": "C", "stationName": "C", "arrivalTime": None,
             "departureTime": 3000, "platform": None},
            {"stationCode": "D", "stationName": "D", "arrivalTime": 4000,
             "departureTime": None, "platform": None},
            {"stationCode": "E", "stationName": "E", "arrivalTime": 5000,
             "departureTime": None, "platform": None},
        ])
        event = _make_event(services=[svc1, svc2])
        fv = self.extractor.extract(event)

        assert fv.service_count == 2
        assert fv.avg_stops == pytest.approx(2.5)

    def test_maintenance_ratio(self) -> None:
        services = [
            _make_service(service_id="s1", affected_by_maintenance=True),
            _make_service(service_id="s2", affected_by_maintenance=False),
            _make_service(service_id="s3", affected_by_maintenance=True),
        ]
        event = _make_event(services=services)
        fv = self.extractor.extract(event)

        assert fv.maintenance_ratio == pytest.approx(2 / 3)

    def test_no_services(self) -> None:
        """Empty services list should not raise and should return sensible defaults."""
        event = _make_event(services=[])
        fv = self.extractor.extract(event)

        assert fv.service_count == 0
        assert fv.earliest_departure_min == 0
        assert fv.latest_arrival_min == 0
        assert fv.avg_stops == 0.0
        assert fv.maintenance_ratio == 0.0

    def test_all_maintenance_affected(self) -> None:
        services = [
            _make_service(service_id=f"s{i}", affected_by_maintenance=True)
            for i in range(3)
        ]
        event = _make_event(services=services)
        fv = self.extractor.extract(event)

        assert fv.maintenance_ratio == pytest.approx(1.0)

    def test_to_numpy_shape(self) -> None:
        event = _make_event(services=[_make_service()])
        fv = self.extractor.extract(event)
        X = self.extractor.to_numpy(fv)

        assert X.shape == (1, 8)
        assert X.dtype == np.float64

    def test_to_numpy_values(self) -> None:
        event = _make_event(
            line_id="TEST",
            effective_date=datetime.date(2026, 1, 5),  # Monday
            services=[_make_service()],
        )
        fv = self.extractor.extract(event)
        X = self.extractor.to_numpy(fv)

        assert X[0, 0] == fv.line_id_hash
        assert X[0, 1] == 0  # Monday
        assert X[0, 2] == 1  # January
        assert X[0, 3] == 1  # one service
        assert X[0, 7] == 0.0  # no maintenance

    def test_invalid_effective_date_falls_back(self) -> None:
        """Out-of-range Avro dates should fall back to 1970-01-01 without raising."""
        event = _make_event()
        event["effectiveDate"] = -999_999_999  # will overflow toordinal
        fv = self.extractor.extract(event)
        # Should not raise; day_of_week/month will be from the fallback date
        assert isinstance(fv, FeatureVector)

    def test_missing_stop_times_default_to_zero(self) -> None:
        """Services with all-null arrival/departure times → 0 defaults."""
        svc = _make_service(stops=[
            {"stationCode": "X", "stationName": "X",
             "arrivalTime": None, "departureTime": None, "platform": None},
        ])
        event = _make_event(services=[svc])
        fv = self.extractor.extract(event)

        assert fv.earliest_departure_min == 0
        assert fv.latest_arrival_min == 0
