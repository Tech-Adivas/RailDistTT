"""Metrics endpoint: GET /metrics.

Exposes all Prometheus metrics in the standard text exposition format consumed by
the Prometheus scraper configured in infra/observability/.
"""

from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from prediction_service.observability.logging import get_logger

logger = get_logger(__name__)

router = APIRouter()

_PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"


@router.get("/metrics")
async def metrics() -> Response:
    """Return Prometheus metrics in text exposition format."""
    data = generate_latest()
    return Response(content=data, media_type=_PROMETHEUS_CONTENT_TYPE)
