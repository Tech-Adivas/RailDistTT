"""Prometheus metrics singletons for the prediction service.

All metrics are module-level so they are registered exactly once per process.
Import this module early (before any worker threads start) to avoid duplicate-registration errors.
"""

from prometheus_client import Counter, Gauge, Histogram

# ---------------------------------------------------------------------------
# Counters
# ---------------------------------------------------------------------------

PREDICTIONS_TOTAL = Counter(
    "predictions_generated_total",
    "Total number of delay predictions generated",
    ["model_version", "status"],
)

PREDICTION_ERRORS_TOTAL = Counter(
    "prediction_errors_total",
    "Total number of prediction pipeline errors",
    ["error_type"],
)

KAFKA_CONSUMED_TOTAL = Counter(
    "kafka_messages_consumed_total",
    "Total number of Kafka messages consumed",
    ["topic"],
)

KAFKA_PUBLISHED_TOTAL = Counter(
    "kafka_messages_published_total",
    "Total number of Kafka messages published",
    ["topic", "status"],
)

# ---------------------------------------------------------------------------
# Histograms
# ---------------------------------------------------------------------------

INFERENCE_DURATION = Histogram(
    "model_inference_duration_seconds",
    "Time taken to run model inference (feature extraction + predict)",
    ["model_version"],
    buckets=[0.05, 0.1, 0.25, 0.5, 1.0, 2.5],
)

KAFKA_PUBLISH_DURATION = Histogram(
    "kafka_publish_duration_seconds",
    "Time taken to publish a prediction event to Kafka (including retries)",
    buckets=[0.01, 0.05, 0.1, 0.5, 1.0],
)

# ---------------------------------------------------------------------------
# Gauges
# ---------------------------------------------------------------------------

CONSUMER_LAG = Gauge(
    "kafka_consumer_lag",
    "Current consumer group lag per topic/partition",
    ["topic", "partition"],
)

MODEL_VERSION_INFO = Gauge(
    "model_version_info",
    "Currently loaded model version (value=1, label carries the version string)",
    ["version"],
)

CIRCUIT_BREAKER_STATE = Gauge(
    "circuit_breaker_state",
    "Circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN",
    ["dependency"],
)
