"""Service configuration via pydantic-settings.

Non-secret config uses the PLACEHOLDER_* convention with # TODO(config): comments.
All secrets come from HashiCorp Vault at runtime; empty-string defaults are for local dev only.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # ---------------------------------------------------------------------------
    # Kafka connectivity
    # TODO(config): Vault path: secret/data/railway/prediction-service/kafka_bootstrap_servers
    # ---------------------------------------------------------------------------
    kafka_bootstrap_servers: str  # injected from Vault in production; set via env var locally

    # SASL credentials (empty = no SASL; for local dev without MSK IAM auth)
    # TODO(config): Vault path: secret/data/railway/prediction-service/kafka_sasl_username
    kafka_sasl_username: str = ""
    # TODO(config): Vault path: secret/data/railway/prediction-service/kafka_sasl_password
    kafka_sasl_password: SecretStr = SecretStr("")

    # Consumer / topic settings
    kafka_consumer_group: str = "railway.prediction-service"
    kafka_topic_schedule: str = "railway.schedule.computed"
    kafka_topic_timetable: str = "railway.timetable.changed"
    kafka_topic_prediction: str = "railway.delay.prediction.computed"
    kafka_topic_dlq: str = "railway.delay.prediction.dlq"

    # ---------------------------------------------------------------------------
    # Schema Registry
    # TODO(config): Replace with the actual MSK Schema Registry URL from Terraform output:
    #               schema_registry_url
    # ---------------------------------------------------------------------------
    schema_registry_url: str = "http://localhost:8081"

    # ---------------------------------------------------------------------------
    # HashiCorp Vault
    # TODO(config): Terraform output: vault_addr
    # ---------------------------------------------------------------------------
    vault_addr: str = "http://localhost:8200"
    vault_role: str = "prediction-service"
    # vault_token is ONLY used in local dev mode (vault_enabled=False or dev Vault instance).
    # Never set a real token here; inject via environment at runtime.
    vault_token: SecretStr = SecretStr("dev-only-root-token")
    vault_enabled: bool = True  # set False for local dev to skip Vault entirely

    # ---------------------------------------------------------------------------
    # MLflow model registry
    # TODO(config): Replace PLACEHOLDER_MLFLOW_TRACKING_URI with the actual MLflow
    #               tracking server URL (Terraform output: mlflow_tracking_uri)
    # ---------------------------------------------------------------------------
    mlflow_tracking_uri: str = "http://localhost:5000"
    mlflow_model_name: str = "delay-predictor"
    mlflow_model_stage: str = "Production"
    mlflow_enabled: bool = False  # False = use mock model (default for local dev)

    # ---------------------------------------------------------------------------
    # Redis (idempotency cache)
    # TODO(config): Replace localhost with the actual ElastiCache endpoint
    #               (Terraform output: redis_primary_endpoint)
    # ---------------------------------------------------------------------------
    redis_host: str = "localhost"
    redis_port: int = 6379
    # TODO(config): Vault path: secret/data/railway/prediction-service/redis_password
    redis_password: SecretStr = SecretStr("")
    redis_ttl_seconds: int = 86400  # 24 hours

    # ---------------------------------------------------------------------------
    # Application
    # ---------------------------------------------------------------------------
    log_level: str = "INFO"
    port: int = 8000

    # ---------------------------------------------------------------------------
    # Resilience
    # ---------------------------------------------------------------------------
    circuit_breaker_fail_max: int = 5
    circuit_breaker_reset_timeout: int = 30
    kafka_producer_retry_attempts: int = 3

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return a cached Settings instance. Cached for the process lifetime."""
    return Settings()
