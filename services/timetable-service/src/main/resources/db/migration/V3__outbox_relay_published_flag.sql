-- V3: Add relay_published flag to outbox_events.
-- The OutboxFallbackRelay queries this column to find events not yet relayed
-- when Debezium is lagging. Consumer idempotency (processed_events.event_id UNIQUE)
-- ensures duplicate publishes from both Debezium and the relay are safe.
ALTER TABLE outbox_events
    ADD COLUMN relay_published BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index: only index unpublished rows (the common query pattern for the relay).
CREATE INDEX idx_outbox_relay_unpublished
    ON outbox_events (created_at)
    WHERE relay_published = FALSE;
