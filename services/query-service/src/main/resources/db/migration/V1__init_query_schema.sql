-- Query Service — initial schema
-- Managed by Flyway; never edit manually.

-- ── Idempotency guard ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS processed_events (
    event_id    VARCHAR(255) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

-- ── Timetable read model ──────────────────────────────────────────────────────
-- Denormalised projection of the current timetable state.
-- Written by the TimetableProjector; read by the TimetableQueryService.
CREATE TABLE IF NOT EXISTS timetable_read_model (
    id              UUID        NOT NULL,
    line_id         VARCHAR(255) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    status          VARCHAR(50)  NOT NULL,
    effective_date  DATE        NOT NULL,
    expiry_date     DATE,
    author_id       VARCHAR(255) NOT NULL,
    reviewer_id     VARCHAR(255),
    version         BIGINT      NOT NULL,
    last_event_id   VARCHAR(255) NOT NULL,
    last_updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_timetable_read_model PRIMARY KEY (id)
);

CREATE INDEX idx_trm_line_id   ON timetable_read_model (line_id);
CREATE INDEX idx_trm_status    ON timetable_read_model (status);
CREATE INDEX idx_trm_effective ON timetable_read_model (effective_date);

-- ── Schedule read model ───────────────────────────────────────────────────────
-- One row per ScheduleComputedEvent (history preserved); queries use
-- ORDER BY computed_at DESC LIMIT 1 to get the latest computation.
CREATE TABLE IF NOT EXISTS schedule_read_model (
    id                  UUID        NOT NULL,
    timetable_id        VARCHAR(255) NOT NULL,
    line_id             VARCHAR(255) NOT NULL,
    effective_date      DATE        NOT NULL,
    expiry_date         DATE,
    schedule_data       TEXT        NOT NULL,
    triggering_event_id VARCHAR(255) NOT NULL,
    computed_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_schedule_read_model PRIMARY KEY (id)
);

CREATE INDEX idx_srm_timetable  ON schedule_read_model (timetable_id);
CREATE INDEX idx_srm_line       ON schedule_read_model (line_id);
CREATE INDEX idx_srm_effective  ON schedule_read_model (effective_date);
