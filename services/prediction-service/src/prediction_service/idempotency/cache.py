"""Redis-backed idempotency cache with in-memory LRU fallback.

Pattern used: check-and-set on Redis key ``pred:processed:{event_id}`` with TTL.
On Redis unavailability, degrades gracefully to an in-memory OrderedDict bounded
at 50 000 entries (oldest entries evicted when the cap is reached).
"""

from __future__ import annotations

import collections
import threading
from typing import TYPE_CHECKING

import redis
from redis.exceptions import RedisError

from prediction_service.observability.logging import get_logger

if TYPE_CHECKING:
    from prediction_service.config import Settings

logger = get_logger(__name__)

_KEY_PREFIX = "pred:processed:"
_MEMORY_MAX = 50_000


class ProcessedEventCache:
    """Redis-backed idempotency cache.

    Falls back to an in-memory LRU (bounded OrderedDict) if Redis is down.
    Thread-safe via a per-instance lock for the in-memory fallback path.
    """

    def __init__(self, settings: "Settings") -> None:
        self._settings = settings
        self._redis: redis.Redis | None = None
        self._memory_cache: collections.OrderedDict[str, bool] = collections.OrderedDict()
        self._lock = threading.Lock()
        self._using_redis = False

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Establish a connection to Redis.

        Does not raise; logs a warning and falls back to in-memory if connection fails.
        """
        password = self._settings.redis_password.get_secret_value() or None
        try:
            client = redis.Redis(
                host=self._settings.redis_host,
                port=self._settings.redis_port,
                password=password,
                decode_responses=True,
                socket_connect_timeout=3,
                socket_timeout=3,
                health_check_interval=30,
            )
            client.ping()
            self._redis = client
            self._using_redis = True
            logger.info(
                "idempotency_cache_connected",
                host=self._settings.redis_host,
                port=self._settings.redis_port,
            )
        except (RedisError, ConnectionError, OSError) as exc:
            logger.warning(
                "redis_unavailable_using_memory",
                error=str(exc),
                message="Falling back to in-memory idempotency cache",
            )
            self._redis = None
            self._using_redis = False

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def is_duplicate(self, event_id: str) -> bool:
        """Return True if this event_id has already been processed."""
        key = _KEY_PREFIX + event_id

        if self._using_redis and self._redis is not None:
            try:
                return bool(self._redis.exists(key))
            except RedisError as exc:
                logger.warning("redis_error_falling_back", error=str(exc))
                self._using_redis = False

        # In-memory fallback
        with self._lock:
            return key in self._memory_cache

    def mark_processed(self, event_id: str) -> None:
        """Mark event_id as processed so future calls to is_duplicate return True."""
        key = _KEY_PREFIX + event_id

        if self._using_redis and self._redis is not None:
            try:
                self._redis.setex(key, self._settings.redis_ttl_seconds, "1")
                return
            except RedisError as exc:
                logger.warning("redis_error_falling_back", error=str(exc))
                self._using_redis = False

        # In-memory fallback with bounded size
        with self._lock:
            if key in self._memory_cache:
                self._memory_cache.move_to_end(key)
            else:
                if len(self._memory_cache) >= _MEMORY_MAX:
                    # Evict the oldest entry
                    self._memory_cache.popitem(last=False)
                self._memory_cache[key] = True

    def is_healthy(self) -> bool:
        """Return True if Redis is reachable (or we are in memory-fallback mode)."""
        if self._redis is None:
            # Memory-only mode — always "healthy" from service perspective
            return True
        try:
            self._redis.ping()
            self._using_redis = True
            return True
        except RedisError:
            return False

    def close(self) -> None:
        """Close the Redis connection."""
        if self._redis is not None:
            try:
                self._redis.close()
            except Exception:  # noqa: BLE001
                pass
            finally:
                self._redis = None
        logger.info("idempotency_cache_closed")
