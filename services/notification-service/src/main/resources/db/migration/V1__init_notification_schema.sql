-- Notification Service — initial schema
-- Managed by Flyway; never edit manually.

-- ── Idempotency guard ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS processed_events (
    event_id    VARCHAR(255) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

-- ── Sent notifications audit trail ────────────────────────────────────────────
-- Immutable record of every delivery attempt (one row per recipient per channel).
-- Retention: indefinite (regulatory/audit requirement).
CREATE TABLE IF NOT EXISTS sent_notifications (
    id                UUID        NOT NULL,
    event_id          VARCHAR(255) NOT NULL,
    recipient_id      VARCHAR(255) NOT NULL,
    channel           VARCHAR(20)  NOT NULL,   -- PUSH / SMS / EMAIL
    notification_type VARCHAR(50)  NOT NULL,
    title             VARCHAR(500) NOT NULL,
    body              TEXT        NOT NULL,
    is_emergency      BOOLEAN     NOT NULL DEFAULT false,
    sent_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id    VARCHAR(255),

    CONSTRAINT pk_sent_notifications PRIMARY KEY (id)
);

CREATE INDEX idx_sn_event_id   ON sent_notifications (event_id);
CREATE INDEX idx_sn_recipient  ON sent_notifications (recipient_id);
CREATE INDEX idx_sn_sent_at    ON sent_notifications (sent_at);
