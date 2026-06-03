# Railway Timetable Distribution Platform — Makefile
# One-command local bring-up and common developer tasks.
#
# Prerequisites:
#   - Docker Engine 27+ and Docker Compose v2
#   - Java 21 (JDK, e.g. Eclipse Temurin)
#   - Node.js 22 LTS
#   - make

COMPOSE := docker compose
MAVEN   := ./mvnw
NG      := npx ng

.PHONY: help up down clean dev test test-unit test-integration build \
        build-images push-images lint format vault-status kafka-topics \
        schema-registry-subjects generate-events logs ps seed-data

# ── Default target ──────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  Railway Timetable Distribution Platform"
	@echo ""
	@echo "  make up                  Start full infra stack (PG, Kafka, Redis, Vault, observability)"
	@echo "  make down                Stop containers (volumes preserved)"
	@echo "  make clean               Stop containers AND wipe volumes (destructive)"
	@echo "  make dev                 Start all services in dev mode against local stack"
	@echo "  make test                Run all unit + integration tests"
	@echo "  make test-unit           Run unit tests only"
	@echo "  make test-integration    Run integration tests (requires infra stack)"
	@echo "  make build               Build all Java services (skip tests)"
	@echo "  make build-images        Build Docker images for all services"
	@echo "  make lint                Run lint + format checks"
	@echo "  make format              Apply auto-formatting (Spotless + Prettier)"
	@echo "  make generate-events     Re-generate Java POJOs from Avro schemas"
	@echo "  make seed-data           Load sample data into all service databases (requires: make up)"
	@echo "  make vault-status        Check Vault health"
	@echo "  make kafka-topics        List Kafka topics"
	@echo "  make logs                Follow logs for all infra containers"
	@echo "  make ps                  Show container status"
	@echo ""

# ── Infrastructure lifecycle ─────────────────────────────────────────────────

# Start the full infra stack and wait for all health checks.
up:
	@echo ">> Starting full infra stack..."
	$(COMPOSE) up -d --wait
	@echo ">> Infra stack is healthy."
	@echo "   Kafka:           localhost:29092"
	@echo "   Schema Registry: http://localhost:8081"
	@echo "   PostgreSQL:      localhost:5432"
	@echo "   Redis:           localhost:6379"
	@echo "   Vault UI:        http://localhost:8200  (token: dev-only-root-token)"
	@echo "   Prometheus:      http://localhost:9090"
	@echo "   Grafana:         http://localhost:3000  (admin/admin)"
	@echo "   Tempo:           http://localhost:3200"
	@echo "   Loki:            http://localhost:3100"

# Stop containers but preserve named volumes.
down:
	$(COMPOSE) down

# Stop containers AND remove all named volumes. Destructive — re-seeds on next `make up`.
clean:
	$(COMPOSE) down -v

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f

# ── Application build ────────────────────────────────────────────────────────

# Build all Java services, skipping tests (tests run in `make test`).
build:
	$(MAVEN) clean package -DskipTests -pl shared/common-lib,shared/events,\
services/timetable-service,services/schedule-service,services/query-service,\
services/distribution-service,services/notification-service,services/api-gateway \
	--also-make

# Re-generate Java POJOs from Avro schemas in shared/events.
generate-events:
	$(MAVEN) generate-sources -pl shared/events

# Build Docker images for all services (multi-stage builds).
build-images: build
	@for svc in timetable-service schedule-service query-service \
	             distribution-service notification-service api-gateway; do \
	  echo ">> Building image for $$svc..."; \
	  docker build -t railway/$$svc:local services/$$svc/; \
	done

# Push images to the configured registry.
# TODO(config): set REGISTRY to your ECR registry URL before using this target.
push-images: build-images
	@REGISTRY=$${REGISTRY:-PLACEHOLDER_ECR_REGISTRY}; \
	for svc in timetable-service schedule-service query-service \
	           distribution-service notification-service api-gateway; do \
	  docker tag railway/$$svc:local $$REGISTRY/$$svc:latest; \
	  docker push $$REGISTRY/$$svc:latest; \
	done

# ── Testing ─────────────────────────────────────────────────────────────────

# Run all tests (unit + integration). Integration tests spin up Testcontainers.
test:
	$(MAVEN) verify -pl shared/common-lib,shared/events,\
services/timetable-service,services/schedule-service,services/query-service,\
services/distribution-service,services/notification-service,services/api-gateway \
	--also-make

# Unit tests only (fast, no containers required).
test-unit:
	$(MAVEN) test -pl shared/common-lib,shared/events,\
services/timetable-service,services/schedule-service,services/query-service,\
services/distribution-service,services/notification-service,services/api-gateway \
	-Dgroups=unit --also-make

# Integration tests (require Docker for Testcontainers).
test-integration:
	$(MAVEN) verify -pl shared/common-lib,shared/events,\
services/timetable-service,services/schedule-service,services/query-service,\
services/distribution-service,services/notification-service,services/api-gateway \
	-Dgroups=integration --also-make

# ── Seed / sample data ───────────────────────────────────────────────────────

seed-data: ## Load sample/seed data into all service databases (requires: make up)
	@echo "Seeding timetable_db..."
	docker compose exec -T postgres psql -U railway -d timetable_db < infra/postgres/seed/01-timetable-db.sql
	@echo "Seeding schedule_db..."
	docker compose exec -T postgres psql -U railway -d schedule_db < infra/postgres/seed/02-schedule-db.sql
	@echo "Seeding query_db..."
	docker compose exec -T postgres psql -U railway -d query_db < infra/postgres/seed/03-query-db.sql
	@echo "Seeding distribution_db..."
	docker compose exec -T postgres psql -U railway -d distribution_db < infra/postgres/seed/04-distribution-db.sql
	@echo "Seeding notification_db..."
	docker compose exec -T postgres psql -U railway -d notification_db < infra/postgres/seed/05-notification-db.sql
	@echo "✓ All databases seeded with sample data."

# ── Code quality ─────────────────────────────────────────────────────────────

# Run all linters (Spotless check, Checkstyle, PMD, SpotBugs, ArchUnit, ESLint).
lint:
	$(MAVEN) spotless:check checkstyle:check pmd:check spotbugs:check \
	  -pl shared/common-lib,shared/events,\
services/timetable-service,services/schedule-service,services/query-service,\
services/distribution-service,services/notification-service,services/api-gateway
	cd frontend/operator-console && npm run lint

# Apply auto-formatting (Spotless for Java, Prettier for TypeScript/HTML).
format:
	$(MAVEN) spotless:apply \
	  -pl shared/common-lib,shared/events,\
services/timetable-service,services/schedule-service,services/query-service,\
services/distribution-service,services/notification-service,services/api-gateway
	cd frontend/operator-console && npm run format

# ── Dev mode ─────────────────────────────────────────────────────────────────

# Start all backend services in Spring Boot dev mode (requires `make up` first).
# Each service uses its local application-local.yml which points at docker-compose infra.
dev: up
	@echo ">> Starting all services in dev mode (background)..."
	$(MAVEN) spring-boot:run -pl services/timetable-service &
	$(MAVEN) spring-boot:run -pl services/schedule-service &
	$(MAVEN) spring-boot:run -pl services/query-service &
	$(MAVEN) spring-boot:run -pl services/distribution-service &
	$(MAVEN) spring-boot:run -pl services/notification-service &
	$(MAVEN) spring-boot:run -pl services/api-gateway &
	cd frontend/operator-console && npm start &
	@echo ">> All services starting. API Gateway: http://localhost:8080"

# ── Observability helpers ─────────────────────────────────────────────────────

vault-status:
	@echo ">> Vault status:"
	@curl -s http://localhost:8200/v1/sys/health | python3 -m json.tool || \
	  echo "Vault not reachable. Run 'make up' first."

kafka-topics:
	$(COMPOSE) exec kafka kafka-topics --bootstrap-server localhost:9092 --list

schema-registry-subjects:
	@curl -s http://localhost:8081/subjects | python3 -m json.tool || \
	  echo "Schema Registry not reachable. Run 'make up' first."
