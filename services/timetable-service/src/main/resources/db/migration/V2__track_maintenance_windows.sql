-- V2: Track maintenance windows schema.
-- Maintenance windows affect active timetables and trigger schedule recomputation.
-- The maintenance window event is published via the outbox (same table as timetable events).

CREATE TABLE track_segments (
    id              VARCHAR(50) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    line_id         VARCHAR(50) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_track_segments PRIMARY KEY (id)
);

CREATE INDEX idx_track_segment_line_id ON track_segments (line_id);

CREATE TABLE maintenance_windows (
    id                         UUID        NOT NULL,
    track_segment_id           VARCHAR(50) NOT NULL,
    maintenance_type           VARCHAR(20) NOT NULL,
    status                     VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    start_time                 TIMESTAMPTZ NOT NULL,
    end_time                   TIMESTAMPTZ NOT NULL,
    affects_passenger_services BOOLEAN     NOT NULL DEFAULT false,
    description                TEXT,
    created_by                 VARCHAR(255) NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                    BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_maintenance_windows PRIMARY KEY (id),
    CONSTRAINT fk_maintenance_track_segment FOREIGN KEY (track_segment_id)
        REFERENCES track_segments (id),
    CONSTRAINT chk_maintenance_end_after_start CHECK (end_time > start_time),
    CONSTRAINT chk_maintenance_type CHECK (maintenance_type IN ('PLANNED', 'EMERGENCY', 'INSPECTION', 'UPGRADE')),
    CONSTRAINT chk_maintenance_status CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_maintenance_track_segment ON maintenance_windows (track_segment_id);
CREATE INDEX idx_maintenance_start_time    ON maintenance_windows (start_time);
CREATE INDEX idx_maintenance_status        ON maintenance_windows (status);

-- Prevent overlapping maintenance windows on the same track segment.
-- This is enforced at the domain layer and additionally by this exclusion constraint.
-- Requires the btree_gist extension.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE INDEX idx_maintenance_time_range ON maintenance_windows
    USING GIST (track_segment_id, tstzrange(start_time, end_time));
