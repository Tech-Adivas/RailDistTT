-- Schedule Service — initial schema
-- Managed by Flyway; never edit manually.

-- ── Idempotency guard ──────────────────────────────────────────────────────────
-- Stores event_ids that have been successfully processed.
-- The UNIQUE constraint on event_id is the race-condition guard:
-- a concurrent duplicate insert will fail, and the losing thread safely skips processing.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id    VARCHAR(255) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

-- ── Computed schedules ────────────────────────────────────────────────────────
-- Persists the latest schedule computation result for each timetable.
-- The query-service projector reads this table to rebuild its read model on startup.
CREATE TABLE IF NOT EXISTS computed_schedules (
    id                  UUID        NOT NULL,
    timetable_id        VARCHAR(255) NOT NULL,
    line_id             VARCHAR(255) NOT NULL,
    effective_date      DATE        NOT NULL,
    expiry_date         DATE,
    triggering_event_id VARCHAR(255) NOT NULL,
    schedule_payload    TEXT        NOT NULL,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_computed_schedules PRIMARY KEY (id)
);

CREATE INDEX idx_computed_timetable   ON computed_schedules (timetable_id);
CREATE INDEX idx_computed_line        ON computed_schedules (line_id);
CREATE INDEX idx_computed_effective   ON computed_schedules (effective_date);
