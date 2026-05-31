-- V1: Initial timetable schema.
-- Creates:
--   timetables         — the main aggregate table with optimistic locking.
--   outbox_events      — Transactional Outbox table (ADR-001). Debezium reads this via CDC.
--   audit_log          — Immutable, append-only audit trail.
--
-- Debezium requires a PostgreSQL publication on the outbox_events table to read the WAL.
-- The publication is created at the bottom of this migration.

-- ── Timetables ────────────────────────────────────────────────────────────────

CREATE TABLE timetables (
    id              UUID        NOT NULL,
    line_id         VARCHAR(50) NOT NULL,
    status          VARCHAR(30) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    effective_date  DATE        NOT NULL,
    expiry_date     DATE,
    author_id       VARCHAR(255) NOT NULL,
    reviewer_id     VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Optimistic locking version. JPA @Version increments this on each UPDATE.
    -- A concurrent update with a stale version results in 0 rows affected → OptimisticLockingFailureException.
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_timetables PRIMARY KEY (id),
    CONSTRAINT chk_timetable_status CHECK (status IN (
        'DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED',
        'ACTIVE', 'EMERGENCY_ACTIVE', 'SUPERSEDED', 'CANCELLED'
    )),
    CONSTRAINT chk_expiry_after_effective CHECK (expiry_date IS NULL OR expiry_date > effective_date)
);

CREATE INDEX idx_timetable_line_id       ON timetables (line_id);
CREATE INDEX idx_timetable_status        ON timetables (status);
CREATE INDEX idx_timetable_effective_date ON timetables (effective_date);
CREATE INDEX idx_timetable_line_status   ON timetables (line_id, status);

-- ── Outbox Events (Transactional Outbox — ADR-001) ────────────────────────────
--
-- This table is the backbone of the Transactional Outbox pattern. Every domain event is
-- written here in the same transaction as the aggregate state change. Debezium reads the
-- WAL and publishes events to Kafka after the transaction commits.
--
-- Column names MUST match debezium.source.transforms.outbox.table.field.* in
-- infra/debezium/application.properties.

CREATE TABLE outbox_events (
    id              UUID        NOT NULL,
    -- Determines the Kafka topic: "timetable" → "railway.timetable.changed"
    aggregate_type  VARCHAR(50) NOT NULL,
    -- Kafka message key — ensures all events for the same aggregate are ordered on one partition.
    aggregate_id    VARCHAR(36) NOT NULL,
    -- Carried as a Kafka record header for consumer routing.
    event_type      VARCHAR(100) NOT NULL,
    -- JSON-serialised event payload. Debezium forwards this as the Kafka message value.
    payload         TEXT        NOT NULL,
    -- Propagated to Kafka as a record header for distributed tracing.
    correlation_id  VARCHAR(36) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_outbox_events PRIMARY KEY (id)
);

CREATE INDEX idx_outbox_aggregate_id ON outbox_events (aggregate_id);
CREATE INDEX idx_outbox_created_at   ON outbox_events (created_at);

-- ── Audit Log (immutable, append-only) ───────────────────────────────────────
--
-- No UPDATE or DELETE is ever issued on this table. Every state-changing operation
-- is recorded here with the actor, before/after status, and optional justification.
-- Regulatory retention: INDEFINITE.

CREATE TABLE audit_log (
    id              UUID        NOT NULL,
    aggregate_type  VARCHAR(50) NOT NULL,
    aggregate_id    VARCHAR(36) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    previous_status VARCHAR(30),
    new_status      VARCHAR(30) NOT NULL,
    actor           VARCHAR(255) NOT NULL,
    correlation_id  VARCHAR(36) NOT NULL,
    -- JSON snapshot of the full aggregate state at the time of the event.
    snapshot        TEXT,
    -- Non-null only for emergency operations — mandatory justification.
    justification   TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

-- Audit log is append-only; disable updates and deletes at the row security level
-- in production using RLS policies. Here we just index for query performance.
CREATE INDEX idx_audit_aggregate_id ON audit_log (aggregate_id);
CREATE INDEX idx_audit_actor        ON audit_log (actor);
CREATE INDEX idx_audit_occurred_at  ON audit_log (occurred_at);

-- ── Debezium Publication ──────────────────────────────────────────────────────
--
-- PostgreSQL logical replication publication for the outbox_events table.
-- Debezium monitors this publication to detect new rows. We scope it to
-- outbox_events only — not the full schema — to minimise WAL volume.
--
-- Note: 'debezium_user' must have the REPLICATION privilege (see infra/postgres/init/).

DO $$
BEGIN
    -- Only create if not already present (idempotent migration).
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication WHERE pubname = 'outbox_publication'
    ) THEN
        EXECUTE 'CREATE PUBLICATION outbox_publication FOR TABLE outbox_events';
    END IF;
END$$;
