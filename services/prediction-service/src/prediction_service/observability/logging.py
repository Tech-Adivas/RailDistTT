"""Structured logging configuration using structlog with JSON output.

Usage:
    from prediction_service.observability.logging import configure_logging, get_logger, bind_correlation_id

    configure_logging("INFO")
    logger = get_logger(__name__)
    bind_correlation_id("abc-123")
    logger.info("event", key="value")
"""

from __future__ import annotations

import logging
import sys

import structlog
from structlog.contextvars import bind_contextvars, clear_contextvars


def configure_logging(log_level: str) -> None:
    """Configure structlog for JSON output with timestamp and log level filtering.

    Should be called once at application startup before any log calls.
    """
    log_level_int = getattr(logging, log_level.upper(), logging.INFO)

    # Configure the standard library root logger so that libraries using
    # stdlib logging are also captured and routed through structlog.
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=log_level_int,
    )

    shared_processors: list[structlog.types.Processor] = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.stdlib.add_logger_name,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
    ]

    structlog.configure(
        processors=[
            *shared_processors,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(log_level_int),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str) -> structlog.stdlib.BoundLogger:
    """Return a structlog bound logger with the given name.

    The logger automatically carries any context vars bound via
    ``bind_correlation_id``.
    """
    return structlog.get_logger(name)


def bind_correlation_id(correlation_id: str) -> None:
    """Bind ``correlation_id`` to the current structlog context var.

    This value will appear in all subsequent log entries emitted from the
    same thread / async context until ``clear_correlation_id()`` is called.
    """
    bind_contextvars(correlation_id=correlation_id)


def clear_correlation_id() -> None:
    """Clear all structlog context vars (including correlation_id)."""
    clear_contextvars()
