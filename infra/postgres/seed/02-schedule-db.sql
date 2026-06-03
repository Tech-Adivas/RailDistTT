-- =============================================================================
-- Seed data for schedule_db
-- Target service: schedule-service (port 8082)
--
-- Computed schedules for ACTIVE, APPROVED, and EMERGENCY_ACTIVE timetables.
-- Timetable UUIDs are consistent with 01-timetable-db.sql.
--
-- Run with:
--   docker compose exec -T postgres psql -U railway -d schedule_db < infra/postgres/seed/02-schedule-db.sql
-- =============================================================================

BEGIN;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM computed_schedules WHERE id = '660e8400-e29b-41d4-a716-446655440001') THEN
    RAISE NOTICE 'Sample data already loaded — skipping.';
    RETURN;
  END IF;
END $$;

-- Computed schedules for the 3 ACTIVE and 1 APPROVED and 1 EMERGENCY_ACTIVE timetables
INSERT INTO computed_schedules (id, timetable_id, line_id, effective_date, expiry_date, triggering_event_id, schedule_payload, computed_at)
VALUES
  -- LINE-VIC-BRI ACTIVE schedule
  ('660e8400-e29b-41d4-a716-446655440001',
   '550e8400-e29b-41d4-a716-446655440001', 'LINE-VIC-BRI',
   CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE + INTERVAL '150 days',
   'evt-timetable-activated-001',
   '{"lineId":"LINE-VIC-BRI","timetableId":"550e8400-e29b-41d4-a716-446655440001","services":[{"serviceId":"SVC-VIC-BRI-0600","departureTime":"06:00","arrivalTime":"06:55","stops":["London Victoria","Clapham Junction","East Croydon","Gatwick Airport","Brighton"],"daysOfOperation":["MON","TUE","WED","THU","FRI"]},{"serviceId":"SVC-VIC-BRI-0630","departureTime":"06:30","arrivalTime":"07:25","stops":["London Victoria","East Croydon","Brighton"],"daysOfOperation":["MON","TUE","WED","THU","FRI"]},{"serviceId":"SVC-VIC-BRI-SAT-0700","departureTime":"07:00","arrivalTime":"07:58","stops":["London Victoria","Clapham Junction","East Croydon","Gatwick Airport","Haywards Heath","Brighton"],"daysOfOperation":["SAT","SUN"]}]}',
   NOW() - INTERVAL '30 days'),

  -- LINE-VIC-BRI APPROVED future schedule
  ('660e8400-e29b-41d4-a716-446655440002',
   '550e8400-e29b-41d4-a716-446655440002', 'LINE-VIC-BRI',
   CURRENT_DATE + INTERVAL '151 days', CURRENT_DATE + INTERVAL '300 days',
   'evt-timetable-approved-002',
   '{"lineId":"LINE-VIC-BRI","timetableId":"550e8400-e29b-41d4-a716-446655440002","services":[{"serviceId":"SVC-VIC-BRI-NEW-0600","departureTime":"06:00","arrivalTime":"06:50","stops":["London Victoria","East Croydon","Gatwick Airport","Brighton"],"daysOfOperation":["MON","TUE","WED","THU","FRI"],"rollingStock":"Class 700 Desiro City"}]}',
   NOW() - INTERVAL '2 days'),

  -- LINE-MCR-LIV ACTIVE schedule
  ('660e8400-e29b-41d4-a716-446655440003',
   '550e8400-e29b-41d4-a716-446655440003', 'LINE-MCR-LIV',
   CURRENT_DATE - INTERVAL '60 days', CURRENT_DATE + INTERVAL '120 days',
   'evt-timetable-activated-003',
   '{"lineId":"LINE-MCR-LIV","timetableId":"550e8400-e29b-41d4-a716-446655440003","services":[{"serviceId":"SVC-MCR-LIV-0700","departureTime":"07:00","arrivalTime":"07:45","stops":["Manchester Piccadilly","Warrington Central","Liverpool Lime Street"],"daysOfOperation":["MON","TUE","WED","THU","FRI","SAT"]},{"serviceId":"SVC-MCR-LIV-0800","departureTime":"08:00","arrivalTime":"08:45","stops":["Manchester Piccadilly","Warrington Central","Liverpool Lime Street"],"daysOfOperation":["MON","TUE","WED","THU","FRI","SAT","SUN"]}]}',
   NOW() - INTERVAL '60 days'),

  -- LINE-BHM-EUS EMERGENCY_ACTIVE schedule
  ('660e8400-e29b-41d4-a716-446655440004',
   '550e8400-e29b-41d4-a716-446655440007', 'LINE-BHM-EUS',
   CURRENT_DATE, CURRENT_DATE + INTERVAL '3 days',
   'evt-emergency-activated-007',
   '{"lineId":"LINE-BHM-EUS","timetableId":"550e8400-e29b-41d4-a716-446655440007","isEmergency":true,"services":[{"serviceId":"SVC-BHM-EUS-EMG-0600","departureTime":"06:00","arrivalTime":"08:30","stops":["Birmingham New Street","Coventry Bus Bridge","Northampton","Milton Keynes","London Euston"],"notes":"DIVERTED VIA NORTHAMPTON — Allow extra 45 minutes","daysOfOperation":["MON","TUE","WED","THU","FRI","SAT","SUN"]}]}',
   NOW() - INTERVAL '2 hours'),

  -- LINE-BRS-PAD ACTIVE schedule
  ('660e8400-e29b-41d4-a716-446655440005',
   '550e8400-e29b-41d4-a716-446655440009', 'LINE-BRS-PAD',
   CURRENT_DATE - INTERVAL '120 days', CURRENT_DATE + INTERVAL '60 days',
   'evt-timetable-activated-009',
   '{"lineId":"LINE-BRS-PAD","timetableId":"550e8400-e29b-41d4-a716-446655440009","services":[{"serviceId":"SVC-BRS-PAD-0630","departureTime":"06:30","arrivalTime":"08:15","stops":["Bristol Temple Meads","Bath Spa","Chippenham","Swindon","Reading","London Paddington"],"class":["Standard","First"],"daysOfOperation":["MON","TUE","WED","THU","FRI"]},{"serviceId":"SVC-BRS-PAD-0730","departureTime":"07:30","arrivalTime":"09:15","stops":["Bristol Temple Meads","Swindon","London Paddington"],"notes":"Express — limited stops","class":["Standard","First"],"daysOfOperation":["MON","TUE","WED","THU","FRI","SAT"]}]}',
   NOW() - INTERVAL '120 days');

-- Processed events (idempotency records for schedule-service)
INSERT INTO processed_events (event_id, event_type, processed_at)
VALUES
  ('evt-timetable-activated-001', 'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '30 days'),
  ('evt-timetable-approved-002',  'TIMETABLE_APPROVED',    NOW() - INTERVAL '2 days'),
  ('evt-timetable-activated-003', 'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '60 days'),
  ('evt-emergency-activated-007', 'EMERGENCY_ACTIVATED',   NOW() - INTERVAL '2 hours'),
  ('evt-timetable-activated-009', 'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '120 days'),
  ('evt-timetable-superseded-008','TIMETABLE_SUPERSEDED',  NOW() - INTERVAL '2 hours');

COMMIT;
