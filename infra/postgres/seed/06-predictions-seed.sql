-- =============================================================================
-- Seed data for query_db — delay_predictions table
-- Target service: query-service (port 8083)
--
-- Six realistic delay predictions covering active routes:
--   LINE-VIC-BRI  (Southern Rail — Victoria to Brighton, ACTIVE)
--   LINE-MCR-LIV  (Northern Rail — Manchester to Liverpool, ACTIVE)
--   LINE-BHM-EUS  (Avanti West Coast — Birmingham to Euston, EMERGENCY_ACTIVE)
--   LINE-BRS-PAD  (GWR — Bristol to Paddington, ACTIVE)
--
-- Run with:
--   docker compose exec -T postgres psql -U railway -d query_db \
--     < infra/postgres/seed/06-predictions-seed.sql
-- =============================================================================

\c query_db

BEGIN;

DO $$ BEGIN
  IF EXISTS (
    SELECT 1 FROM delay_predictions
    WHERE event_id = 'evt-prediction-seed-001'
  ) THEN
    RAISE NOTICE 'Delay prediction seed data already loaded — skipping.';
    RETURN;
  END IF;

  -- ── LINE-VIC-BRI: Line-level prediction (moderate confidence, minor delay) ──
  INSERT INTO delay_predictions
    (route_id, train_id, predicted_delay_minutes, confidence_score,
     model_version, predicted_at, event_id)
  VALUES
    ('LINE-VIC-BRI', NULL, 4, 0.82,
     '2.3.1', NOW() - INTERVAL '5 minutes', 'evt-prediction-seed-001');

  -- ── LINE-VIC-BRI: Service-level prediction for a specific train ──────────────
  INSERT INTO delay_predictions
    (route_id, train_id, predicted_delay_minutes, confidence_score,
     model_version, predicted_at, event_id)
  VALUES
    ('LINE-VIC-BRI', 'SN-17:42-VIC-BTN', 7, 0.91,
     '2.3.1', NOW() - INTERVAL '3 minutes', 'evt-prediction-seed-002');

  -- ── LINE-MCR-LIV: On-time prediction (high confidence) ──────────────────────
  INSERT INTO delay_predictions
    (route_id, train_id, predicted_delay_minutes, confidence_score,
     model_version, predicted_at, event_id)
  VALUES
    ('LINE-MCR-LIV', NULL, 0, 0.95,
     '2.3.1', NOW() - INTERVAL '8 minutes', 'evt-prediction-seed-003');

  -- ── LINE-BHM-EUS: Emergency service — significant delay, lower confidence ────
  -- is_emergency=true timetable LINE-BHM-EUS (550e8400-e29b-41d4-a716-446655440007)
  INSERT INTO delay_predictions
    (route_id, train_id, predicted_delay_minutes, confidence_score,
     model_version, predicted_at, event_id)
  VALUES
    ('LINE-BHM-EUS', NULL, 22, 0.63,
     '2.3.1', NOW() - INTERVAL '1 minute', 'evt-prediction-seed-004');

  -- ── LINE-BHM-EUS: Service-level prediction for diverted train ────────────────
  INSERT INTO delay_predictions
    (route_id, train_id, predicted_delay_minutes, confidence_score,
     model_version, predicted_at, event_id)
  VALUES
    ('LINE-BHM-EUS', 'AW-14:05-BHM-EUS', 35, 0.71,
     '2.3.1', NOW() - INTERVAL '30 seconds', 'evt-prediction-seed-005');

  -- ── LINE-BRS-PAD: Minor delay, very high confidence ─────────────────────────
  INSERT INTO delay_predictions
    (route_id, train_id, predicted_delay_minutes, confidence_score,
     model_version, predicted_at, event_id)
  VALUES
    ('LINE-BRS-PAD', NULL, 2, 0.97,
     '2.3.1', NOW() - INTERVAL '10 minutes', 'evt-prediction-seed-006');

END $$;

COMMIT;
