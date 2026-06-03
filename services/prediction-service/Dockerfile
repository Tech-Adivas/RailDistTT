# ---------------------------------------------------------------------------
# Stage 1 — builder: install Python dependencies into an isolated directory
# ---------------------------------------------------------------------------
FROM python:3.11-slim AS builder

WORKDIR /build

# Install build tools required for some native extensions
RUN apt-get update && apt-get install -y --no-install-recommends \
        gcc \
        g++ \
        libssl-dev \
        libffi-dev \
        curl \
    && rm -rf /var/lib/apt/lists/*

# Copy only requirements first to leverage Docker layer cache
COPY requirements.txt .

RUN pip install --upgrade pip && \
    pip install --prefix=/build/deps --no-cache-dir -r requirements.txt

# ---------------------------------------------------------------------------
# Stage 2 — runtime: minimal image with non-root user
# ---------------------------------------------------------------------------
FROM python:3.11-slim AS runtime

LABEL org.opencontainers.image.title="prediction-service" \
      org.opencontainers.image.description="ML delay prediction microservice" \
      org.opencontainers.image.version="1.0.0"

# Runtime-only system dependencies (e.g. for confluent-kafka native lib)
RUN apt-get update && apt-get install -y --no-install-recommends \
        libssl3 \
        curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root user (UID 1000)
RUN groupadd --gid 1000 appgroup && \
    useradd --uid 1000 --gid appgroup --shell /sbin/nologin --no-create-home appuser

# Copy installed packages from builder
COPY --from=builder /build/deps /usr/local

# Copy application source
WORKDIR /app
COPY src/ ./src/

# Ensure the package is on PYTHONPATH
ENV PYTHONPATH=/app/src \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

USER appuser

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD curl --silent --fail http://localhost:8000/health || exit 1

CMD ["python", "-m", "prediction_service.main"]
