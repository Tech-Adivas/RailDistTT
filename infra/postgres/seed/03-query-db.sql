-- =============================================================================
-- Seed data for query_db
-- Target service: query-service (port 8083)
--
-- CQRS read model — mirrors the write-side timetable_db projections.
-- Timetable UUIDs are consistent with 01-timetable-db.sql.
--
-- Run with:
--   docker compose exec -T postgres psql -U railway -d query_db < infra/postgres/seed/03-query-db.sql
-- =============================================================================

BEGIN;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM timetable_read_model WHERE id = '550e8400-e29b-41d4-a716-446655440001') THEN
    RAISE NOTICE 'Sample data already loaded — skipping.';
    RETURN;
  END IF;
END $$;

-- Read model mirrors timetable_db exactly (CQRS projection)
INSERT INTO timetable_read_model (id, line_id, name, description, status, effective_date, expiry_date, author_id, reviewer_id, version, last_event_id, last_updated_at)
VALUES
  ('550e8400-e29b-41d4-a716-446655440001', 'LINE-VIC-BRI', 'Southern Rail Summer 2025 — Victoria to Brighton',
   'Standard weekday and weekend service. 12 trains per hour peak, 6 off-peak. Journey time 55 minutes.',
   'ACTIVE', CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '150 days',
   'user-emma-thompson', 'user-sarah-mitchell', 3, 'evt-timetable-activated-001', NOW() - INTERVAL '30 days'),

  ('550e8400-e29b-41d4-a716-446655440002', 'LINE-VIC-BRI', 'Southern Rail Autumn/Winter 2025 — Victoria to Brighton',
   'Revised service incorporating new rolling stock. Reduced journey time to 50 minutes.',
   'APPROVED', CURRENT_DATE + INTERVAL '151 days', CURRENT_DATE + INTERVAL '300 days',
   'user-emma-thompson', 'user-sarah-mitchell', 2, 'evt-timetable-approved-002', NOW() - INTERVAL '2 days'),

  ('550e8400-e29b-41d4-a716-446655440003', 'LINE-MCR-LIV', 'Northern Rail 2025 — Manchester Piccadilly to Liverpool Lime Street',
   'Hourly service with additional peak services. Journey time 45 minutes. Calls at Warrington Central.',
   'ACTIVE', CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '120 days',
   'user-james-wilson', 'user-david-clarke', 4, 'evt-timetable-activated-003', NOW() - INTERVAL '60 days'),

  ('550e8400-e29b-41d4-a716-446655440004', 'LINE-MCR-LIV', 'Northern Rail 2026 Q1 — Manchester Piccadilly to Liverpool Lime Street',
   'Extended service with new Sunday timetable. First departure 07:00, last 23:30.',
   'PENDING_REVIEW', CURRENT_DATE + INTERVAL '121 days', CURRENT_DATE + INTERVAL '270 days',
   'user-james-wilson', NULL, 1, 'evt-submitted-for-review-004', NOW() - INTERVAL '1 day'),

  ('550e8400-e29b-41d4-a716-446655440005', 'LINE-EDI-GLA', 'ScotRail 2026 — Edinburgh Waverley to Glasgow Central (Draft)',
   'Work in progress. Proposed electrification upgrade schedule.',
   'DRAFT', CURRENT_DATE + INTERVAL '200 days', NULL,
   'user-emma-thompson', NULL, 0, 'evt-timetable-created-005', NOW() - INTERVAL '1 day'),

  ('550e8400-e29b-41d4-a716-446655440006', 'LINE-EDI-GLA', 'ScotRail 2025 H2 — Edinburgh Waverley to Glasgow Central',
   'Rejected: timing conflicts with CrossCountry services at Glasgow Central.',
   'REJECTED', CURRENT_DATE + INTERVAL '30 days', CURRENT_DATE + INTERVAL '180 days',
   'user-emma-thompson', 'user-david-clarke', 2, 'evt-timetable-rejected-006', NOW() - INTERVAL '8 days'),

  ('550e8400-e29b-41d4-a716-446655440007', 'LINE-BHM-EUS', 'Avanti West Coast EMERGENCY — Birmingham to London Euston (Track Incident)',
   'EMERGENCY SERVICE: Reduced frequency due to track obstruction at Coventry.',
   'EMERGENCY_ACTIVE', CURRENT_DATE, CURRENT_DATE + INTERVAL '3 days',
   'user-michael-chen', 'user-david-clarke', 1, 'evt-emergency-activated-007', NOW() - INTERVAL '2 hours'),

  ('550e8400-e29b-41d4-a716-446655440008', 'LINE-BHM-EUS', 'Avanti West Coast 2025 — Birmingham New Street to London Euston',
   'Standard service superseded by emergency timetable.',
   'SUPERSEDED', CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE + INTERVAL '90 days',
   'user-james-wilson', 'user-sarah-mitchell', 4, 'evt-timetable-superseded-008', NOW() - INTERVAL '2 hours'),

  ('550e8400-e29b-41d4-a716-446655440009', 'LINE-BRS-PAD', 'GWR 2025 — Bristol Temple Meads to London Paddington',
   'Express service. Journey time 1h 45m. Two trains per hour. First class available.',
   'ACTIVE', CURRENT_DATE - INTERVAL '120 days', CURRENT_DATE + INTERVAL '60 days',
   'user-james-wilson', 'user-david-clarke', 5, 'evt-timetable-activated-009', NOW() - INTERVAL '120 days'),

  ('550e8400-e29b-41d4-a716-44665544000a', 'LINE-BRS-PAD', 'GWR 2025 H2 — Bristol Temple Meads to London Paddington (Cancelled)',
   'Cancelled: proposed service suspended pending infrastructure investment decision.',
   'CANCELLED', CURRENT_DATE + INTERVAL '61 days', CURRENT_DATE + INTERVAL '210 days',
   'user-emma-thompson', NULL, 1, 'evt-timetable-cancelled-00a', NOW() - INTERVAL '5 days');

-- Schedule read model (mirrors schedule_db)
INSERT INTO schedule_read_model (id, timetable_id, line_id, effective_date, expiry_date, schedule_data, triggering_event_id, computed_at)
VALUES
  ('770e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440001', 'LINE-VIC-BRI',
   CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '150 days',
   '{"services":3,"peakFrequency":"12/hour","offPeakFrequency":"6/hour","journeyMinutes":55}',
   'evt-timetable-activated-001', NOW() - INTERVAL '30 days'),

  ('770e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440003', 'LINE-MCR-LIV',
   CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '120 days',
   '{"services":2,"frequency":"hourly","journeyMinutes":45,"stops":["Manchester Piccadilly","Warrington Central","Liverpool Lime Street"]}',
   'evt-timetable-activated-003', NOW() - INTERVAL '60 days'),

  ('770e8400-e29b-41d4-a716-446655440007', '550e8400-e29b-41d4-a716-446655440007', 'LINE-BHM-EUS',
   CURRENT_DATE, CURRENT_DATE + INTERVAL '3 days',
   '{"services":1,"isEmergency":true,"journeyMinutes":150,"notes":"DIVERTED VIA NORTHAMPTON"}',
   'evt-emergency-activated-007', NOW() - INTERVAL '2 hours'),

  ('770e8400-e29b-41d4-a716-446655440009', '550e8400-e29b-41d4-a716-446655440009', 'LINE-BRS-PAD',
   CURRENT_DATE - INTERVAL '120 days', CURRENT_DATE + INTERVAL '60 days',
   '{"services":2,"frequency":"2/hour","journeyMinutes":105,"class":["Standard","First"]}',
   'evt-timetable-activated-009', NOW() - INTERVAL '120 days');

-- Processed events (idempotency records for query-service)
INSERT INTO processed_events (event_id, event_type, processed_at)
VALUES
  ('evt-timetable-created-001',      'TIMETABLE_CREATED',       NOW() - INTERVAL '45 days'),
  ('evt-submitted-for-review-001',   'SUBMITTED_FOR_REVIEW',    NOW() - INTERVAL '40 days'),
  ('evt-timetable-approved-001',     'TIMETABLE_APPROVED',      NOW() - INTERVAL '35 days'),
  ('evt-timetable-activated-001',    'TIMETABLE_ACTIVATED',     NOW() - INTERVAL '30 days'),
  ('evt-timetable-created-002',      'TIMETABLE_CREATED',       NOW() - INTERVAL '10 days'),
  ('evt-timetable-approved-002',     'TIMETABLE_APPROVED',      NOW() - INTERVAL '2 days'),
  ('evt-timetable-activated-003',    'TIMETABLE_ACTIVATED',     NOW() - INTERVAL '60 days'),
  ('evt-submitted-for-review-004',   'SUBMITTED_FOR_REVIEW',    NOW() - INTERVAL '1 day'),
  ('evt-timetable-created-005',      'TIMETABLE_CREATED',       NOW() - INTERVAL '1 day'),
  ('evt-timetable-rejected-006',     'TIMETABLE_REJECTED',      NOW() - INTERVAL '8 days'),
  ('evt-emergency-activated-007',    'EMERGENCY_ACTIVATED',     NOW() - INTERVAL '2 hours'),
  ('evt-timetable-superseded-008',   'TIMETABLE_SUPERSEDED',    NOW() - INTERVAL '2 hours'),
  ('evt-timetable-activated-009',    'TIMETABLE_ACTIVATED',     NOW() - INTERVAL '120 days'),
  ('evt-timetable-cancelled-00a',    'TIMETABLE_CANCELLED',     NOW() - INTERVAL '5 days');

COMMIT;
