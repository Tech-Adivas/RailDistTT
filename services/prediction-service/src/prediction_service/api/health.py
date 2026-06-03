"""Health-check endpoint: GET /health.

Dependency health statuses are cached for 5 seconds to ensure sub-100 ms
response times under load. A background asyncio task refreshes the cache.
"""

from __future__ import annotations

import asyncio
import time
from typing import Any

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from prediction_service.observability.logging import get_logger

logger = get_logger(__name__)

router = APIRouter()

# ---------------------------------------------------------------------------
# Shared health state — populated by the background refresh task
# ---------------------------------------------------------------------------

_health_cache: dict[str, Any] = {
    "status": "STARTING",
    "checks": {},
    "last_updated": 0.0,
}
_cache_ttl_seconds = 5.0
_refresh_lock = asyncio.Lock()

# Registry of named health-check callables injected by main.py
_dependency_checks: dict[str, Any] = {}


def register_health_check(name: str, fn: Any) -> None:
    """Register a zero-argument callable returning bool for dependency *name*."""
    _dependency_checks[name] = fn


async def _do_health_refresh() -> None:
    """Run all registered health checks and update the cache."""
    checks: dict[str, str] = {}
    overall_up = True

    for name, fn in _dependency_checks.items():
        try:
            is_up: bool = fn()
            checks[name] = "UP" if is_up else "DOWN"
            if not is_up:
                overall_up = False
        except Exception as exc:  # noqa: BLE001
            checks[name] = "DOWN"
            overall_up = False
            logger.warning("health_check_exception", dependency=name, error=str(exc))

    _health_cache["status"] = "UP" if overall_up else "DOWN"
    _health_cache["checks"] = checks
    _health_cache["last_updated"] = time.monotonic()


async def start_health_refresh_loop() -> None:
    """Background coroutine: refresh health every 5 seconds."""
    while True:
        async with _refresh_lock:
            await _do_health_refresh()
        await asyncio.sleep(_cache_ttl_seconds)


# ---------------------------------------------------------------------------
# Route
# ---------------------------------------------------------------------------


@router.get("/health")
async def health() -> JSONResponse:
    """Return cached health status. Responds within 100 ms."""
    age = time.monotonic() - _health_cache["last_updated"]
    if age > _cache_ttl_seconds * 2:
        # Cache is stale — do a synchronous refresh once
        async with _refresh_lock:
            await _do_health_refresh()

    body = {
        "status": _health_cache["status"],
        "checks": _health_cache["checks"],
    }
    status_code = 200 if _health_cache["status"] == "UP" else 503
    return JSONResponse(content=body, status_code=status_code)
