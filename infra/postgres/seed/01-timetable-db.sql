-- =============================================================================
-- Seed data for timetable_db
-- Target service: timetable-service (port 8081)
--
-- Covers all 8 timetable status values:
--   DRAFT, PENDING_REVIEW, APPROVED, ACTIVE, REJECTED,
--   EMERGENCY_ACTIVE, SUPERSEDED, CANCELLED
--
-- Run with:
--   docker compose exec -T postgres psql -U railway -d timetable_db < infra/postgres/seed/01-timetable-db.sql
-- =============================================================================

-- Wrap in a transaction so partial inserts are impossible
BEGIN;

-- Guard against double-seeding
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM timetables WHERE id = '550e8400-e29b-41d4-a716-446655440001') THEN
    RAISE NOTICE 'Sample data already loaded — skipping.';
    RETURN;
  END IF;
END $$;

INSERT INTO timetables (id, line_id, status, name, description, effective_date, expiry_date, author_id, reviewer_id, version, created_at, updated_at)
VALUES
  -- ACTIVE: London Victoria → Brighton Summer 2025
  ('550e8400-e29b-41d4-a716-446655440001', 'LINE-VIC-BRI', 'ACTIVE',
   'Southern Rail Summer 2025 — Victoria to Brighton',
   'Standard weekday and weekend service. 12 trains per hour peak, 6 off-peak. Journey time 55 minutes.',
   CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '150 days',
   'user-emma-thompson', 'user-sarah-mitchell', 3,
   NOW() - INTERVAL '45 days', NOW() - INTERVAL '30 days'),

  -- APPROVED (future): Victoria → Brighton Autumn 2025 — will supersede TT-001
  ('550e8400-e29b-41d4-a716-446655440002', 'LINE-VIC-BRI', 'APPROVED',
   'Southern Rail Autumn/Winter 2025 — Victoria to Brighton',
   'Revised service incorporating new rolling stock. Reduced journey time to 50 minutes.',
   CURRENT_DATE + INTERVAL '151 days', CURRENT_DATE + INTERVAL '300 days',
   'user-emma-thompson', 'user-sarah-mitchell', 2,
   NOW() - INTERVAL '10 days', NOW() - INTERVAL '2 days'),

  -- ACTIVE: Manchester → Liverpool
  ('550e8400-e29b-41d4-a716-446655440003', 'LINE-MCR-LIV', 'ACTIVE',
   'Northern Rail 2025 — Manchester Piccadilly to Liverpool Lime Street',
   'Hourly service with additional peak services. Journey time 45 minutes. Calls at Warrington Central.',
   CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '120 days',
   'user-james-wilson', 'user-david-clarke', 4,
   NOW() - INTERVAL '75 days', NOW() - INTERVAL '60 days'),

  -- PENDING_REVIEW: Manchester → Liverpool next period
  ('550e8400-e29b-41d4-a716-446655440004', 'LINE-MCR-LIV', 'PENDING_REVIEW',
   'Northern Rail 2026 Q1 — Manchester Piccadilly to Liverpool Lime Street',
   'Extended service with new Sunday timetable. First departure 07:00, last 23:30.',
   CURRENT_DATE + INTERVAL '121 days', CURRENT_DATE + INTERVAL '270 days',
   'user-james-wilson', NULL, 1,
   NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day'),

  -- DRAFT: Edinburgh → Glasgow (work in progress)
  ('550e8400-e29b-41d4-a716-446655440005', 'LINE-EDI-GLA', 'DRAFT',
   'ScotRail 2026 — Edinburgh Waverley to Glasgow Central (Draft)',
   'Work in progress. Proposed electrification upgrade schedule. Draft timings subject to change.',
   CURRENT_DATE + INTERVAL '200 days', NULL,
   'user-emma-thompson', NULL, 0,
   NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day'),

  -- REJECTED: Edinburgh → Glasgow previous attempt
  ('550e8400-e29b-41d4-a716-446655440006', 'LINE-EDI-GLA', 'REJECTED',
   'ScotRail 2025 H2 — Edinburgh Waverley to Glasgow Central',
   'Rejected: timing conflicts with CrossCountry services at Glasgow Central. Requires rescheduling.',
   CURRENT_DATE + INTERVAL '30 days', CURRENT_DATE + INTERVAL '180 days',
   'user-emma-thompson', 'user-david-clarke', 2,
   NOW() - INTERVAL '15 days', NOW() - INTERVAL '8 days'),

  -- EMERGENCY_ACTIVE: Birmingham → London (track incident)
  ('550e8400-e29b-41d4-a716-446655440007', 'LINE-BHM-EUS', 'EMERGENCY_ACTIVE',
   'Avanti West Coast EMERGENCY — Birmingham to London Euston (Track Incident)',
   'EMERGENCY SERVICE: Reduced frequency due to track obstruction at Coventry. Services diverted via Northampton.',
   CURRENT_DATE, CURRENT_DATE + INTERVAL '3 days',
   'user-michael-chen', 'user-david-clarke', 1,
   NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours'),

  -- SUPERSEDED: Birmingham → London (old timetable, superseded by emergency)
  ('550e8400-e29b-41d4-a716-446655440008', 'LINE-BHM-EUS', 'SUPERSEDED',
   'Avanti West Coast 2025 — Birmingham New Street to London Euston',
   'Standard service superseded by emergency timetable due to track obstruction.',
   CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE + INTERVAL '90 days',
   'user-james-wilson', 'user-sarah-mitchell', 4,
   NOW() - INTERVAL '95 days', NOW() - INTERVAL '2 hours'),

  -- ACTIVE: Bristol → London
  ('550e8400-e29b-41d4-a716-446655440009', 'LINE-BRS-PAD', 'ACTIVE',
   'GWR 2025 — Bristol Temple Meads to London Paddington',
   'Express service. Journey time 1h 45m. Two trains per hour. First class available.',
   CURRENT_DATE - INTERVAL '120 days', CURRENT_DATE + INTERVAL '60 days',
   'user-james-wilson', 'user-david-clarke', 5,
   NOW() - INTERVAL '130 days', NOW() - INTERVAL '120 days'),

  -- CANCELLED: Bristol → London (cancelled before activation)
  ('550e8400-e29b-41d4-a716-44665544000a', 'LINE-BRS-PAD', 'CANCELLED',
   'GWR 2025 H2 — Bristol Temple Meads to London Paddington (Cancelled)',
   'Cancelled: proposed service suspended pending infrastructure investment decision.',
   CURRENT_DATE + INTERVAL '61 days', CURRENT_DATE + INTERVAL '210 days',
   'user-emma-thompson', NULL, 1,
   NOW() - INTERVAL '20 days', NOW() - INTERVAL '5 days');


-- AUDIT LOG — full workflow history for each timetable
INSERT INTO audit_log (id, aggregate_type, aggregate_id, event_type, previous_status, new_status, actor, correlation_id, justification, occurred_at)
VALUES
  -- TT-001 (ACTIVE): full workflow
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440001', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430c8', NULL, NOW() - INTERVAL '45 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440001', 'SUBMITTED_FOR_REVIEW',    'DRAFT',          'PENDING_REVIEW',  'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430c9', NULL, NOW() - INTERVAL '40 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440001', 'TIMETABLE_APPROVED',      'PENDING_REVIEW', 'APPROVED',        'user-sarah-mitchell','6ba7b810-9dad-11d1-80b4-00c04fd430ca', NULL, NOW() - INTERVAL '35 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440001', 'TIMETABLE_ACTIVATED',     'APPROVED',       'ACTIVE',          'system-scheduler',  '6ba7b810-9dad-11d1-80b4-00c04fd430cb', NULL, NOW() - INTERVAL '30 days'),

  -- TT-002 (APPROVED): submitted and approved
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440002', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430cc', NULL, NOW() - INTERVAL '10 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440002', 'SUBMITTED_FOR_REVIEW',    'DRAFT',          'PENDING_REVIEW',  'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430cd', NULL, NOW() - INTERVAL '7 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440002', 'TIMETABLE_APPROVED',      'PENDING_REVIEW', 'APPROVED',        'user-sarah-mitchell','6ba7b810-9dad-11d1-80b4-00c04fd430ce', NULL, NOW() - INTERVAL '2 days'),

  -- TT-003 (ACTIVE): full workflow
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440003', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-james-wilson',  '6ba7b810-9dad-11d1-80b4-00c04fd430cf', NULL, NOW() - INTERVAL '75 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440003', 'SUBMITTED_FOR_REVIEW',    'DRAFT',          'PENDING_REVIEW',  'user-james-wilson',  '6ba7b810-9dad-11d1-80b4-00c04fd430d0', NULL, NOW() - INTERVAL '70 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440003', 'TIMETABLE_APPROVED',      'PENDING_REVIEW', 'APPROVED',        'user-david-clarke',  '6ba7b810-9dad-11d1-80b4-00c04fd430d1', NULL, NOW() - INTERVAL '65 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440003', 'TIMETABLE_ACTIVATED',     'APPROVED',       'ACTIVE',          'system-scheduler',  '6ba7b810-9dad-11d1-80b4-00c04fd430d2', NULL, NOW() - INTERVAL '60 days'),

  -- TT-004 (PENDING_REVIEW): created then submitted
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440004', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-james-wilson',  '6ba7b810-9dad-11d1-80b4-00c04fd430d3', NULL, NOW() - INTERVAL '3 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440004', 'SUBMITTED_FOR_REVIEW',    'DRAFT',          'PENDING_REVIEW',  'user-james-wilson',  '6ba7b810-9dad-11d1-80b4-00c04fd430d4', NULL, NOW() - INTERVAL '1 day'),

  -- TT-005 (DRAFT): just created
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440005', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430d5', NULL, NOW() - INTERVAL '1 day'),

  -- TT-006 (REJECTED): created, submitted, changes requested, resubmitted, rejected
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440006', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430d6', NULL, NOW() - INTERVAL '15 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440006', 'SUBMITTED_FOR_REVIEW',    'DRAFT',          'PENDING_REVIEW',  'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430d7', NULL, NOW() - INTERVAL '12 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440006', 'CHANGES_REQUESTED',       'PENDING_REVIEW', 'DRAFT',           'user-david-clarke',  '6ba7b810-9dad-11d1-80b4-00c04fd430d8', 'Timing conflicts with CrossCountry at Glasgow Central. Please revise arrival times.', NOW() - INTERVAL '11 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440006', 'SUBMITTED_FOR_REVIEW',    'DRAFT',          'PENDING_REVIEW',  'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430d9', NULL, NOW() - INTERVAL '10 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440006', 'TIMETABLE_REJECTED',      'PENDING_REVIEW', 'REJECTED',        'user-david-clarke',  '6ba7b810-9dad-11d1-80b4-00c04fd430da', 'Revised timings still conflict. Fundamentally incompatible with CrossCountry franchise agreement. Requires complete redesign.', NOW() - INTERVAL '8 days'),

  -- TT-007 (EMERGENCY_ACTIVE): emergency activation
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440007', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-michael-chen',  '6ba7b810-9dad-11d1-80b4-00c04fd430db', NULL, NOW() - INTERVAL '2 hours 30 minutes'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440007', 'EMERGENCY_ACTIVATED',     'DRAFT',          'EMERGENCY_ACTIVE','user-michael-chen',  '6ba7b810-9dad-11d1-80b4-00c04fd430dc', 'TRACK INCIDENT: Signal failure and track obstruction at Coventry. Estimated 72h resolution. Emergency reduced-frequency service required immediately.', NOW() - INTERVAL '2 hours'),
  -- TT-008 auto-superseded when TT-007 was emergency-activated
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440008', 'TIMETABLE_SUPERSEDED',    'ACTIVE',         'SUPERSEDED',      'user-michael-chen',  '6ba7b810-9dad-11d1-80b4-00c04fd430dc', 'Auto-superseded by emergency activation of TT-007', NOW() - INTERVAL '2 hours'),

  -- TT-008 (SUPERSEDED): full history before supersession
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440008', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-james-wilson',  '6ba7b810-9dad-11d1-80b4-00c04fd430dd', NULL, NOW() - INTERVAL '95 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440008', 'SUBMITTED_FOR_REVIEW',    'DRAFT',          'PENDING_REVIEW',  'user-james-wilson',  '6ba7b810-9dad-11d1-80b4-00c04fd430de', NULL, NOW() - INTERVAL '92 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440008', 'TIMETABLE_APPROVED',      'PENDING_REVIEW', 'APPROVED',        'user-sarah-mitchell','6ba7b810-9dad-11d1-80b4-00c04fd430df', NULL, NOW() - INTERVAL '90 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440008', 'TIMETABLE_ACTIVATED',     'APPROVED',       'ACTIVE',          'system-scheduler',  '6ba7b810-9dad-11d1-80b4-00c04fd430e0', NULL, NOW() - INTERVAL '90 days'),

  -- TT-009 (ACTIVE): full workflow
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440009', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-james-wilson',  '6ba7b810-9dad-11d1-80b4-00c04fd430e1', NULL, NOW() - INTERVAL '130 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440009', 'SUBMITTED_FOR_REVIEW',    'DRAFT',          'PENDING_REVIEW',  'user-james-wilson',  '6ba7b810-9dad-11d1-80b4-00c04fd430e2', NULL, NOW() - INTERVAL '127 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440009', 'TIMETABLE_APPROVED',      'PENDING_REVIEW', 'APPROVED',        'user-david-clarke',  '6ba7b810-9dad-11d1-80b4-00c04fd430e3', NULL, NOW() - INTERVAL '125 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-446655440009', 'TIMETABLE_ACTIVATED',     'APPROVED',       'ACTIVE',          'system-scheduler',  '6ba7b810-9dad-11d1-80b4-00c04fd430e4', NULL, NOW() - INTERVAL '120 days'),

  -- TT-00a (CANCELLED)
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-44665544000a', 'TIMETABLE_CREATED',       NULL,             'DRAFT',          'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430e5', NULL, NOW() - INTERVAL '20 days'),
  (gen_random_uuid(), 'timetable', '550e8400-e29b-41d4-a716-44665544000a', 'TIMETABLE_CANCELLED',     'DRAFT',          'CANCELLED',       'user-emma-thompson', '6ba7b810-9dad-11d1-80b4-00c04fd430e6', 'Infrastructure investment decision postponed by DfT. Service not viable without electrification upgrade.', NOW() - INTERVAL '5 days');


-- TRACK SEGMENTS
INSERT INTO track_segments (id, name, line_id, description, created_at)
VALUES
  ('SEG-VIC-CRO', 'London Victoria to Croydon',         'LINE-VIC-BRI', 'Urban section, multiple stopping points, complex signalling', NOW() - INTERVAL '2 years'),
  ('SEG-CRO-GAT', 'Croydon to Gatwick Airport',         'LINE-VIC-BRI', 'Airport connector section, high-frequency service', NOW() - INTERVAL '2 years'),
  ('SEG-GAT-BRI', 'Gatwick Airport to Brighton',         'LINE-VIC-BRI', 'Coastal approach, susceptible to weather delays', NOW() - INTERVAL '2 years'),
  ('SEG-MCR-WAR', 'Manchester to Warrington Central',    'LINE-MCR-LIV', 'Electrified section, mixed freight and passenger', NOW() - INTERVAL '2 years'),
  ('SEG-WAR-LIV', 'Warrington Central to Liverpool Lime Street', 'LINE-MCR-LIV', 'High-speed section', NOW() - INTERVAL '2 years'),
  ('SEG-EDI-HAY', 'Edinburgh Waverley to Haymarket',     'LINE-EDI-GLA', 'Tunnel section, single track constraints', NOW() - INTERVAL '2 years'),
  ('SEG-HAY-GLA', 'Haymarket to Glasgow Central',        'LINE-EDI-GLA', 'Main inter-city section, 160 km/h permitted', NOW() - INTERVAL '2 years'),
  ('SEG-BHM-COV', 'Birmingham New Street to Coventry',   'LINE-BHM-EUS', 'Currently disrupted — track obstruction active', NOW() - INTERVAL '2 years'),
  ('SEG-COV-MKY', 'Coventry to Milton Keynes',           'LINE-BHM-EUS', 'High-speed section, 200 km/h permitted', NOW() - INTERVAL '2 years'),
  ('SEG-BRS-CHI', 'Bristol Temple Meads to Chippenham',  'LINE-BRS-PAD', 'Brunel mainline, electrification upgrade planned', NOW() - INTERVAL '2 years'),
  ('SEG-CHI-SWI', 'Chippenham to Swindon',               'LINE-BRS-PAD', 'High-speed section, 201 km/h', NOW() - INTERVAL '2 years');


-- MAINTENANCE WINDOWS
INSERT INTO maintenance_windows (id, track_segment_id, maintenance_type, status, start_time, end_time, affects_passenger_services, description, created_by, created_at, updated_at, version)
VALUES
  -- Planned future maintenance (Croydon→Gatwick)
  (gen_random_uuid(), 'SEG-CRO-GAT', 'PLANNED', 'PLANNED',
   NOW() + INTERVAL '10 days', NOW() + INTERVAL '10 days 6 hours',
   true, 'Overnight track inspection and ballast tamping. Buses replace trains 01:00–06:00.',
   'user-david-clarke', NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days', 0),

  -- Recently completed (Manchester→Warrington)
  (gen_random_uuid(), 'SEG-MCR-WAR', 'INSPECTION', 'COMPLETED',
   NOW() - INTERVAL '7 days', NOW() - INTERVAL '6 days 18 hours',
   false, 'Routine signalling system inspection. No passenger impact. Completed on schedule.',
   'user-david-clarke', NOW() - INTERVAL '14 days', NOW() - INTERVAL '6 days', 1),

  -- ACTIVE emergency maintenance — caused TT-007 emergency activation!
  (gen_random_uuid(), 'SEG-BHM-COV', 'EMERGENCY', 'ACTIVE',
   NOW() - INTERVAL '2 hours 30 minutes', NOW() + INTERVAL '70 hours',
   true, 'EMERGENCY: Track obstruction and signal failure following freight derailment near Coventry. Full line closure. Network Rail recovery teams on site.',
   'user-michael-chen', NOW() - INTERVAL '2 hours 30 minutes', NOW() - INTERVAL '2 hours', 0),

  -- Planned upgrade (Bristol→Chippenham)
  (gen_random_uuid(), 'SEG-BRS-CHI', 'UPGRADE', 'PLANNED',
   NOW() + INTERVAL '65 days', NOW() + INTERVAL '72 days',
   true, 'Electrification infrastructure installation — OLE mast foundations. Buses replace trains throughout.',
   'user-james-wilson', NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days', 0);

COMMIT;
