# Multi-stage build for timetable-service.
# See platform/service-template/Dockerfile for the canonical template.

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY shared/common-lib/pom.xml shared/common-lib/pom.xml
COPY shared/events/pom.xml shared/events/pom.xml
COPY services/timetable-service/pom.xml services/timetable-service/pom.xml
RUN ./mvnw dependency:go-offline -q
COPY . .
RUN ./mvnw clean package -DskipTests \
    -pl shared/common-lib,shared/events,services/timetable-service \
    --also-make -q

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S railway && adduser -S railway -G railway
USER railway
WORKDIR /app

ADD --chown=railway:railway \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.16.0/opentelemetry-javaagent.jar \
    /app/opentelemetry-javaagent.jar

COPY --from=builder --chown=railway:railway \
    /build/services/timetable-service/target/*.jar /app/app.jar

EXPOSE 8081

ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME="timetable-service" \
    OTEL_EXPORTER_OTLP_ENDPOINT="http://PLACEHOLDER_OTEL_ENDPOINT:4317" \
    OTEL_EXPORTER_OTLP_PROTOCOL="grpc"

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1
