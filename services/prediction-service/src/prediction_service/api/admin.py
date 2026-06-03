"""Admin endpoints — model hot-reload.

POST /admin/reload-model triggers an in-process model reload without restart.
A non-empty Authorization: Bearer <token> header is required — full OIDC
validation is performed by the API Gateway upstream.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from fastapi import APIRouter, Header, HTTPException, status
from fastapi.responses import JSONResponse

from prediction_service.inference.engine import InferenceEngine
from prediction_service.observability.logging import get_logger

if TYPE_CHECKING:
    pass

logger = get_logger(__name__)

router = APIRouter()

# The engine singleton is injected at startup via module-level assignment.
_engine: InferenceEngine | None = None


def set_engine(engine: InferenceEngine) -> None:
    """Inject the InferenceEngine singleton (called from main.py lifespan)."""
    global _engine  # noqa: PLW0603
    _engine = engine


@router.post("/admin/reload-model")
async def reload_model(
    authorization: str = Header(default="", alias="Authorization"),
) -> JSONResponse:
    """Hot-reload the Production model from the model registry.

    Requires a non-empty ``Authorization: Bearer <token>`` header.
    Full OIDC token validation is delegated to the API Gateway.

    Returns 200 ``{"status": "ok", "new_version": "..."}`` on success.
    Returns 401 if no token is present.
    Returns 500 on reload failure.
    """
    # Validate that a Bearer token is present (OIDC validation is at the gateway)
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid Authorization header. Expected: Bearer <token>",
        )
    token = authorization[len("bearer "):].strip()
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Empty Bearer token",
        )

    if _engine is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Inference engine not initialised",
        )

    try:
        new_version = _engine.reload()
        logger.info("admin_model_reloaded", new_version=new_version)
        return JSONResponse(content={"status": "ok", "new_version": new_version})
    except Exception as exc:  # noqa: BLE001
        logger.error("admin_reload_failed", error=str(exc))
        return JSONResponse(
            content={"status": "error", "message": str(exc)},
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        )
