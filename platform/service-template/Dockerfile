# Multi-stage Dockerfile for Railway platform services.
# Stage 1: Build the fat JAR.
# Stage 2: Run with a minimal JRE image + OTel Java agent.
#
# Usage: docker build -t railway/<service-name>:latest .
# The OTel agent is configured via JAVA_TOOL_OPTIONS at runtime.

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and POM files first to exploit layer caching.
# When only source changes, the dependency download step is skipped.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY shared/common-lib/pom.xml shared/common-lib/pom.xml
COPY shared/events/pom.xml shared/events/pom.xml
# TODO(config): add the correct service module path.
COPY services/PLACEHOLDER_SERVICE_NAME/pom.xml services/PLACEHOLDER_SERVICE_NAME/pom.xml

RUN ./mvnw dependency:go-offline -q

# Copy all source and build.
COPY . .
RUN ./mvnw clean package -DskipTests \
    -pl shared/common-lib,shared/events,services/PLACEHOLDER_SERVICE_NAME \
    --also-make -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Do not run as root — security best practice.
RUN addgroup -S railway && adduser -S railway -G railway
USER railway

WORKDIR /app

# Download OpenTelemetry Java agent (version pinned in docs/VERSIONS.md).
# In production, bake this into a base image to avoid per-build downloads.
ADD --chown=railway:railway \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.16.0/opentelemetry-javaagent.jar \
    /app/opentelemetry-javaagent.jar

COPY --from=builder --chown=railway:railway \
    /build/services/PLACEHOLDER_SERVICE_NAME/target/*.jar \
    /app/app.jar

# Expose Actuator port for health checks.
EXPOSE 8080

# OTel agent is attached via JAVA_TOOL_OPTIONS.
# TODO(config): set OTEL_EXPORTER_OTLP_ENDPOINT to your OTel collector.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME="PLACEHOLDER_SERVICE_NAME" \
    OTEL_EXPORTER_OTLP_ENDPOINT="http://PLACEHOLDER_OTEL_ENDPOINT:4317" \
    OTEL_EXPORTER_OTLP_PROTOCOL="grpc"

# Use Spring Boot layered JARs for better Docker layer caching.
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
