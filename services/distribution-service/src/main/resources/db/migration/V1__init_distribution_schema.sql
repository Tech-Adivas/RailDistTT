-- Distribution Service — initial schema
-- Managed by Flyway; never edit manually.

-- ── Idempotency guard ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS processed_events (
    event_id    VARCHAR(255) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

-- ── Per-channel distribution tracking ─────────────────────────────────────────
-- Records the outcome of each channel delivery attempt for a given ScheduleComputedEvent.
-- Used for saga compensation queries and operational dashboards.
CREATE TABLE IF NOT EXISTS distribution_tracking (
    id               UUID        NOT NULL,
    timetable_id     VARCHAR(255) NOT NULL,
    schedule_event_id VARCHAR(255) NOT NULL,
    channel          VARCHAR(50)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,   -- PUBLISHED / FAILED / COMPENSATED
    failure_reason   TEXT,
    is_emergency     BOOLEAN     NOT NULL DEFAULT false,
    distributed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_distribution_tracking PRIMARY KEY (id)
);

CREATE INDEX idx_dt_timetable     ON distribution_tracking (timetable_id);
CREATE INDEX idx_dt_schedule_event ON distribution_tracking (schedule_event_id);
CREATE INDEX idx_dt_status        ON distribution_tracking (status);
