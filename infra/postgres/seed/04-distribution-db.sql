-- =============================================================================
-- Seed data for distribution_db
-- Target service: distribution-service (port 8084)
--
-- Distribution tracking records for all fan-out channels.
-- Timetable UUIDs are consistent with 01-timetable-db.sql.
--
-- Run with:
--   docker compose exec -T postgres psql -U railway -d distribution_db < infra/postgres/seed/04-distribution-db.sql
-- =============================================================================

BEGIN;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM distribution_tracking WHERE schedule_event_id = 'evt-timetable-activated-001') THEN
    RAISE NOTICE 'Sample data already loaded — skipping.';
    RETURN;
  END IF;
END $$;

-- Distribution tracking for ACTIVE timetables (4 channels each)
INSERT INTO distribution_tracking (id, timetable_id, schedule_event_id, channel, status, failure_reason, is_emergency, distributed_at)
VALUES
  -- TT-001 (LINE-VIC-BRI ACTIVE) — all 4 channels published
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440001', 'evt-timetable-activated-001', 'WEBSOCKET_PUSH',   'PUBLISHED', NULL, false, NOW() - INTERVAL '30 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440001', 'evt-timetable-activated-001', 'PASSENGER_APP',    'PUBLISHED', NULL, false, NOW() - INTERVAL '30 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440001', 'evt-timetable-activated-001', 'STATION_DISPLAY',  'PUBLISHED', NULL, false, NOW() - INTERVAL '30 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440001', 'evt-timetable-activated-001', 'PARTNER_FEED',     'PUBLISHED', NULL, false, NOW() - INTERVAL '30 days'),

  -- TT-003 (LINE-MCR-LIV ACTIVE) — all channels published except PARTNER_FEED which failed
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440003', 'evt-timetable-activated-003', 'WEBSOCKET_PUSH',   'PUBLISHED', NULL, false, NOW() - INTERVAL '60 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440003', 'evt-timetable-activated-003', 'PASSENGER_APP',    'PUBLISHED', NULL, false, NOW() - INTERVAL '60 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440003', 'evt-timetable-activated-003', 'STATION_DISPLAY',  'PUBLISHED', NULL, false, NOW() - INTERVAL '60 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440003', 'evt-timetable-activated-003', 'PARTNER_FEED',     'FAILED', 'Partner API timeout after 30s — partner-feed-api.nationalrail.co.uk unreachable', false, NOW() - INTERVAL '60 days'),

  -- TT-007 (LINE-BHM-EUS EMERGENCY_ACTIVE) — emergency broadcast to all channels
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440007', 'evt-emergency-activated-007', 'WEBSOCKET_PUSH',   'PUBLISHED', NULL, true, NOW() - INTERVAL '2 hours'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440007', 'evt-emergency-activated-007', 'PASSENGER_APP',    'PUBLISHED', NULL, true, NOW() - INTERVAL '2 hours'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440007', 'evt-emergency-activated-007', 'STATION_DISPLAY',  'PUBLISHED', NULL, true, NOW() - INTERVAL '2 hours'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440007', 'evt-emergency-activated-007', 'PARTNER_FEED',     'PUBLISHED', NULL, true, NOW() - INTERVAL '2 hours'),
  -- TT-008 supersession event distribution
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440008', 'evt-timetable-superseded-008', 'WEBSOCKET_PUSH',  'PUBLISHED', NULL, true, NOW() - INTERVAL '2 hours'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440008', 'evt-timetable-superseded-008', 'PASSENGER_APP',   'PUBLISHED', NULL, true, NOW() - INTERVAL '2 hours'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440008', 'evt-timetable-superseded-008', 'STATION_DISPLAY', 'PUBLISHED', NULL, true, NOW() - INTERVAL '2 hours'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440008', 'evt-timetable-superseded-008', 'PARTNER_FEED',    'PUBLISHED', NULL, true, NOW() - INTERVAL '2 hours'),

  -- TT-009 (LINE-BRS-PAD ACTIVE) — all channels published
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440009', 'evt-timetable-activated-009', 'WEBSOCKET_PUSH',   'PUBLISHED', NULL, false, NOW() - INTERVAL '120 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440009', 'evt-timetable-activated-009', 'PASSENGER_APP',    'PUBLISHED', NULL, false, NOW() - INTERVAL '120 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440009', 'evt-timetable-activated-009', 'STATION_DISPLAY',  'PUBLISHED', NULL, false, NOW() - INTERVAL '120 days'),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440009', 'evt-timetable-activated-009', 'PARTNER_FEED',     'PUBLISHED', NULL, false, NOW() - INTERVAL '120 days');

-- Processed events
INSERT INTO processed_events (event_id, event_type, processed_at)
VALUES
  ('evt-timetable-activated-001',    'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '30 days'),
  ('evt-timetable-activated-003',    'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '60 days'),
  ('evt-emergency-activated-007',    'EMERGENCY_ACTIVATED',   NOW() - INTERVAL '2 hours'),
  ('evt-timetable-superseded-008',   'TIMETABLE_SUPERSEDED',  NOW() - INTERVAL '2 hours'),
  ('evt-timetable-activated-009',    'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '120 days');

COMMIT;
