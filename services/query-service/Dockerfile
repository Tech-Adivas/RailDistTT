# =============================================================================
# Stage 1: Build
# Uses the official Maven image with Temurin JDK 21 to compile and package.
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

# Copy root POM and all module POMs first.
# This layer is cached as long as POM files don't change, so subsequent
# source-only changes skip the slow dependency:go-offline step.
COPY pom.xml .
COPY shared/common-lib/pom.xml shared/common-lib/pom.xml
COPY shared/events/pom.xml shared/events/pom.xml
COPY services/query-service/pom.xml services/query-service/pom.xml

# Resolve all dependencies into the local Maven cache.
# Only re-runs when a POM changes (layer cache miss).
RUN mvn -B -pl services/query-service -am dependency:go-offline -q

# Copy source code for shared libs and the target service.
COPY shared/ shared/
COPY services/query-service/ services/query-service/

# Build the fat JAR. Tests are skipped here because they run in the CI
# build job (ci-backend.yml) before this Dockerfile is invoked.
RUN mvn -B -pl services/query-service -am clean package -DskipTests

# =============================================================================
# Stage 2: Runtime
# Minimal JRE-only Alpine image — no build tools, no source code.
# =============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Create a non-root system user/group for the JVM process.
# Running as root inside a container is a security anti-pattern.
RUN addgroup -S railway && adduser -S railway -G railway
USER railway

# Copy only the executable fat JAR from the builder stage.
COPY --from=builder /workspace/services/query-service/target/query-service-*.jar app.jar

# JVM tuning flags:
#   -XX:+UseContainerSupport        honour cgroup memory/CPU limits (default on JDK 11+, explicit here for clarity)
#   -XX:MaxRAMPercentage=75.0       use up to 75 % of the container's RAM for the JVM heap
#   -XX:+UseG1GC                    use G1 garbage collector (good balance for microservices)
#   -Djava.security.egd=...         faster SecureRandom seeding in containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

# Application port
EXPOSE 8083
# Spring Boot Actuator / management port
EXPOSE 8090

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
