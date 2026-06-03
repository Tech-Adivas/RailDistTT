"""HashiCorp Vault client with Kubernetes or token auth and background token renewal.

In production: authenticates using the Kubernetes service account token.
In local dev (vault_enabled=False): all methods are no-ops; callers read secrets from env vars.
"""

from __future__ import annotations

import threading
import time
from typing import TYPE_CHECKING

import hvac

from prediction_service.observability.logging import get_logger

if TYPE_CHECKING:
    from prediction_service.config import Settings

logger = get_logger(__name__)

_K8S_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
_RENEWAL_FRACTION = 0.75  # renew at 75% of TTL


class VaultClient:
    """Fetches secrets from HashiCorp Vault with Kubernetes auth or token auth (dev mode)."""

    def __init__(self, settings: "Settings") -> None:
        self._settings = settings
        self._client: hvac.Client | None = None
        self._renewal_thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def authenticate(self) -> None:
        """Authenticate using K8s service account token or VAULT_TOKEN (dev mode).

        Retries 5 times with exponential back-off: 2s, 4s, 8s, 16s, 32s.
        Raises RuntimeError if all retries are exhausted.
        """
        if not self._settings.vault_enabled:
            logger.info("vault_disabled", message="Vault is disabled; skipping authentication.")
            return

        delays = [2, 4, 8, 16, 32]
        last_exc: Exception | None = None

        for attempt, delay in enumerate(delays, start=1):
            try:
                self._do_authenticate()
                logger.info("vault_authenticated", attempt=attempt)
                self.start_renewal_thread()
                return
            except Exception as exc:  # noqa: BLE001
                last_exc = exc
                logger.warning(
                    "vault_auth_retry",
                    attempt=attempt,
                    delay_seconds=delay,
                    error=str(exc),
                )
                if attempt < len(delays):
                    time.sleep(delay)

        raise RuntimeError(
            f"Failed to authenticate with Vault after {len(delays)} attempts"
        ) from last_exc

    def get_secret(self, path: str, key: str) -> str:
        """Read a single key from a KV v2 secret path.

        Args:
            path: Secret path relative to the KV v2 mount, e.g.
                  ``"railway/prediction-service"``
            key:  The key within the secret map to return.

        Raises:
            NotImplementedError: when vault_enabled=False (use env vars in dev).
            KeyError: if the secret path or key does not exist.
            RuntimeError: if the Vault client is not authenticated.
        """
        if not self._settings.vault_enabled:
            raise NotImplementedError(
                "Vault is disabled (vault_enabled=False). "
                "Set the secret via an environment variable instead."
            )

        with self._lock:
            if self._client is None:
                raise RuntimeError("VaultClient is not authenticated. Call authenticate() first.")
            client = self._client

        try:
            response = client.secrets.kv.v2.read_secret_version(path=path)
            data: dict = response["data"]["data"]
        except Exception as exc:
            raise RuntimeError(f"Failed to read secret at path '{path}': {exc}") from exc

        if key not in data:
            raise KeyError(f"Key '{key}' not found in Vault secret at path '{path}'")

        return data[key]

    def start_renewal_thread(self) -> None:
        """Start a background thread that renews the Vault token at 75% of its TTL."""
        if not self._settings.vault_enabled:
            return
        if self._renewal_thread and self._renewal_thread.is_alive():
            return

        self._stop_event.clear()
        self._renewal_thread = threading.Thread(
            target=self._renewal_loop,
            name="vault-token-renewer",
            daemon=True,
        )
        self._renewal_thread.start()
        logger.info("vault_renewal_thread_started")

    def is_healthy(self) -> bool:
        """Return True if the Vault token is valid (or Vault is disabled)."""
        if not self._settings.vault_enabled:
            return True
        with self._lock:
            if self._client is None:
                return False
            try:
                return bool(self._client.is_authenticated())
            except Exception:  # noqa: BLE001
                return False

    def close(self) -> None:
        """Signal the renewal thread to stop and release resources."""
        self._stop_event.set()
        if self._renewal_thread and self._renewal_thread.is_alive():
            self._renewal_thread.join(timeout=5)
        logger.info("vault_client_closed")

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _do_authenticate(self) -> None:
        """Attempt a single authentication against Vault."""
        client = hvac.Client(url=self._settings.vault_addr)

        # Try Kubernetes auth first (production path)
        try:
            with open(_K8S_TOKEN_PATH) as fh:
                jwt = fh.read().strip()
            client.auth.kubernetes.login(
                role=self._settings.vault_role,
                jwt=jwt,
            )
            logger.info("vault_auth_method", method="kubernetes")
        except FileNotFoundError:
            # Fall back to token auth (dev / CI path)
            token = self._settings.vault_token.get_secret_value()
            client.token = token
            if not client.is_authenticated():
                raise RuntimeError("Vault token authentication failed")
            logger.info("vault_auth_method", method="token")

        with self._lock:
            self._client = client

    def _renewal_loop(self) -> None:
        """Run in background; renew the token before it expires."""
        while not self._stop_event.is_set():
            try:
                with self._lock:
                    client = self._client

                if client is None:
                    time.sleep(10)
                    continue

                lookup = client.auth.token.lookup_self()
                ttl: int = lookup["data"].get("ttl", 0)
                renewable: bool = lookup["data"].get("renewable", False)

                if ttl <= 0 or not renewable:
                    # Non-renewable (e.g. root token in dev mode) — sleep and re-check
                    self._stop_event.wait(timeout=60)
                    continue

                sleep_seconds = max(1, int(ttl * _RENEWAL_FRACTION))
                logger.debug(
                    "vault_token_ttl",
                    ttl_seconds=ttl,
                    renewing_in_seconds=sleep_seconds,
                )
                self._stop_event.wait(timeout=sleep_seconds)

                if self._stop_event.is_set():
                    break

                with self._lock:
                    client = self._client
                if client:
                    client.auth.token.renew_self()
                    logger.info("vault_token_renewed")

            except Exception as exc:  # noqa: BLE001
                logger.warning("vault_renewal_error", error=str(exc))
                self._stop_event.wait(timeout=30)
