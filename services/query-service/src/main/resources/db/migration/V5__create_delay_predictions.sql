-- Query Service — delay predictions table
-- Managed by Flyway; never edit manually.

CREATE TABLE delay_predictions (
    prediction_id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id                VARCHAR(255) NOT NULL,
    train_id                VARCHAR(255),
    predicted_delay_minutes INT         NOT NULL CHECK (predicted_delay_minutes >= 0),
    confidence_score        FLOAT       NOT NULL CHECK (confidence_score >= 0.0 AND confidence_score <= 1.0),
    model_version           VARCHAR(50) NOT NULL,
    predicted_at            TIMESTAMPTZ NOT NULL,
    event_id                VARCHAR(255) NOT NULL UNIQUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delay_predictions_route_id     ON delay_predictions (route_id);
CREATE INDEX idx_delay_predictions_train_id     ON delay_predictions (train_id) WHERE train_id IS NOT NULL;
CREATE INDEX idx_delay_predictions_predicted_at ON delay_predictions (predicted_at DESC);
