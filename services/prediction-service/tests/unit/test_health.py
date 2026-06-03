"""Unit tests for the /health endpoint."""

from __future__ import annotations

from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from prediction_service.api.health import _dependency_checks, _health_cache, router


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _reset_health_state() -> None:
    """Reset module-level health state between tests."""
    _dependency_checks.clear()
    _health_cache["status"] = "STARTING"
    _health_cache["checks"] = {}
    _health_cache["last_updated"] = 0.0


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def reset_state():
    """Reset health module state before each test."""
    _reset_health_state()
    yield
    _reset_health_state()


@pytest.fixture()
def client():
    """FastAPI TestClient with only the health router."""
    from fastapi import FastAPI

    app = FastAPI()
    app.include_router(router)
    return TestClient(app, raise_server_exceptions=False)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestHealthEndpoint:
    def test_all_up_returns_200(self, client: TestClient) -> None:
        from prediction_service.api.health import register_health_check

        register_health_check("consumer", lambda: True)
        register_health_check("producer", lambda: True)
        register_health_check("model", lambda: True)

        response = client.get("/health")

        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "UP"
        assert all(v == "UP" for v in body["checks"].values())

    def test_one_down_returns_503(self, client: TestClient) -> None:
        from prediction_service.api.health import register_health_check

        register_health_check("consumer", lambda: True)
        register_health_check("producer", lambda: False)  # DOWN
        register_health_check("model", lambda: True)

        response = client.get("/health")

        assert response.status_code == 503
        body = response.json()
        assert body["status"] == "DOWN"
        assert body["checks"]["producer"] == "DOWN"
        assert body["checks"]["consumer"] == "UP"

    def test_all_down_returns_503(self, client: TestClient) -> None:
        from prediction_service.api.health import register_health_check

        register_health_check("consumer", lambda: False)
        register_health_check("model", lambda: False)

        response = client.get("/health")

        assert response.status_code == 503
        body = response.json()
        assert body["status"] == "DOWN"

    def test_check_raising_exception_counts_as_down(self, client: TestClient) -> None:
        from prediction_service.api.health import register_health_check

        def _bad_check() -> bool:
            raise RuntimeError("health check crashed")

        register_health_check("consumer", lambda: True)
        register_health_check("broken", _bad_check)

        response = client.get("/health")

        assert response.status_code == 503
        body = response.json()
        assert body["checks"]["broken"] == "DOWN"

    def test_no_checks_returns_up(self, client: TestClient) -> None:
        """With no registered checks, service reports UP (nothing is down)."""
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "UP"

    def test_response_contains_checks_dict(self, client: TestClient) -> None:
        from prediction_service.api.health import register_health_check

        register_health_check("redis", lambda: True)

        body = client.get("/health").json()
        assert "checks" in body
        assert "redis" in body["checks"]
