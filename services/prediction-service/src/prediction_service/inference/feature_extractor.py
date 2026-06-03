"""Feature extraction from ScheduleComputedEvent Avro dicts.

Converts raw event dicts (as returned by the Avro deserializer) into a
fixed-size numeric feature vector suitable for scikit-learn inference.
"""

from __future__ import annotations

import datetime
from dataclasses import dataclass

import numpy as np

# Avro ``date`` logical type encodes days since 1970-01-01 (Unix epoch = day 0).
# Python's datetime.date.fromordinal uses the proleptic Gregorian calendar where
# day 1 = 0001-01-01, so the offset from Avro epoch (1970-01-01) to ordinal day 1
# is 719163.
_AVRO_DATE_ORDINAL_OFFSET = 719163


@dataclass
class FeatureVector:
    """All features derived from a single ScheduleComputedEvent."""

    line_id_hash: int  # hash(lineId) % 1000 — stable numeric proxy for the line
    day_of_week: int  # 0 = Monday … 6 = Sunday
    month: int  # 1–12
    service_count: int  # total number of services in the schedule
    earliest_departure_min: int  # earliest departure in minutes since midnight, 0 if unknown
    latest_arrival_min: int  # latest arrival in minutes since midnight, 0 if unknown
    avg_stops: float  # mean number of stops across all services
    maintenance_ratio: float  # fraction of services with affectedByMaintenance=True


class FeatureExtractor:
    """Extract a :class:`FeatureVector` from a raw ScheduleComputedEvent dict."""

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def extract(self, event: dict) -> FeatureVector:
        """Extract features from a deserialized ScheduleComputedEvent dict.

        The ``event`` dict is produced by the Avro deserializer and mirrors the
        Avro schema field names (camelCase).

        Args:
            event: Deserialized ScheduleComputedEvent as a plain Python dict.

        Returns:
            A :class:`FeatureVector` instance.
        """
        line_id: str = event.get("lineId", "")
        line_id_hash = abs(hash(line_id)) % 1000

        effective_date_days: int = event.get("effectiveDate", 0)
        try:
            date = datetime.date.fromordinal(effective_date_days + _AVRO_DATE_ORDINAL_OFFSET)
        except (ValueError, OverflowError):
            date = datetime.date(1970, 1, 1)

        day_of_week = date.weekday()  # 0=Monday
        month = date.month

        services: list[dict] = event.get("services", []) or []
        service_count = len(services)

        # Collect all departure / arrival times (Avro time-millis → ms since midnight)
        departures_min: list[int] = []
        arrivals_min: list[int] = []
        stop_counts: list[int] = []
        maintenance_count = 0

        for svc in services:
            stops: list[dict] = svc.get("stops", []) or []
            stop_counts.append(len(stops))

            for stop in stops:
                dep = stop.get("departureTime")
                if dep is not None:
                    departures_min.append(dep // 60_000)  # ms → minutes
                arr = stop.get("arrivalTime")
                if arr is not None:
                    arrivals_min.append(arr // 60_000)

            if svc.get("affectedByMaintenance", False):
                maintenance_count += 1

        earliest_departure_min = min(departures_min) if departures_min else 0
        latest_arrival_min = max(arrivals_min) if arrivals_min else 0
        avg_stops = float(sum(stop_counts) / len(stop_counts)) if stop_counts else 0.0
        maintenance_ratio = (
            float(maintenance_count / service_count) if service_count > 0 else 0.0
        )

        return FeatureVector(
            line_id_hash=line_id_hash,
            day_of_week=day_of_week,
            month=month,
            service_count=service_count,
            earliest_departure_min=earliest_departure_min,
            latest_arrival_min=latest_arrival_min,
            avg_stops=avg_stops,
            maintenance_ratio=maintenance_ratio,
        )

    def to_numpy(self, fv: FeatureVector) -> np.ndarray:
        """Convert a :class:`FeatureVector` to a ``(1, 8)`` float64 numpy array.

        Column order:
            0: line_id_hash
            1: day_of_week
            2: month
            3: service_count
            4: earliest_departure_min
            5: latest_arrival_min
            6: avg_stops
            7: maintenance_ratio
        """
        return np.array(
            [
                [
                    fv.line_id_hash,
                    fv.day_of_week,
                    fv.month,
                    fv.service_count,
                    fv.earliest_departure_min,
                    fv.latest_arrival_min,
                    fv.avg_stops,
                    fv.maintenance_ratio,
                ]
            ],
            dtype=np.float64,
        )
