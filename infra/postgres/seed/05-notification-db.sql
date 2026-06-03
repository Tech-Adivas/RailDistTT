-- =============================================================================
-- Seed data for notification_db
-- Target service: notification-service (port 8085)
--
-- Sent notifications for timetable activation and emergency events.
-- Timetable UUIDs are consistent with 01-timetable-db.sql.
--
-- Run with:
--   docker compose exec -T postgres psql -U railway -d notification_db < infra/postgres/seed/05-notification-db.sql
-- =============================================================================

BEGIN;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM sent_notifications WHERE event_id = 'evt-timetable-activated-001' LIMIT 1) THEN
    RAISE NOTICE 'Sample data already loaded — skipping.';
    RETURN;
  END IF;
END $$;

-- Sent notifications for all activation and emergency events
INSERT INTO sent_notifications (id, event_id, recipient_id, channel, notification_type, title, body, is_emergency, sent_at, correlation_id)
VALUES
  -- TT-001 activation notifications (LINE-VIC-BRI — 5 sample passengers)
  (gen_random_uuid(), 'evt-timetable-activated-001', 'passenger-uuid-001', 'PUSH',  'TIMETABLE_ACTIVATED', 'New timetable active on Victoria–Brighton', 'The Southern Rail summer 2025 timetable is now active on the London Victoria to Brighton line. 12 trains per hour during peak times.', false, NOW() - INTERVAL '30 days', '6ba7b810-9dad-11d1-80b4-00c04fd430cb'),
  (gen_random_uuid(), 'evt-timetable-activated-001', 'passenger-uuid-002', 'PUSH',  'TIMETABLE_ACTIVATED', 'New timetable active on Victoria–Brighton', 'The Southern Rail summer 2025 timetable is now active on the London Victoria to Brighton line. 12 trains per hour during peak times.', false, NOW() - INTERVAL '30 days', '6ba7b810-9dad-11d1-80b4-00c04fd430cb'),
  (gen_random_uuid(), 'evt-timetable-activated-001', 'passenger-uuid-003', 'EMAIL', 'TIMETABLE_ACTIVATED', 'Your saved route: Victoria to Brighton — new timetable active', 'Dear passenger, the Southern Rail Summer 2025 timetable is now active. Your saved journey from London Victoria to Brighton now runs on the updated schedule. Journey time: 55 minutes.', false, NOW() - INTERVAL '30 days', '6ba7b810-9dad-11d1-80b4-00c04fd430cb'),
  (gen_random_uuid(), 'evt-timetable-activated-001', 'passenger-uuid-004', 'SMS',   'TIMETABLE_ACTIVATED', 'Victoria-Brighton timetable update', 'Southern Rail: New timetable active from today. Victoria to Brighton 55 mins, 12tph peak. Details: nationalrail.co.uk', false, NOW() - INTERVAL '30 days', '6ba7b810-9dad-11d1-80b4-00c04fd430cb'),

  -- TT-003 activation notifications (LINE-MCR-LIV)
  (gen_random_uuid(), 'evt-timetable-activated-003', 'passenger-uuid-005', 'PUSH',  'TIMETABLE_ACTIVATED', 'Manchester–Liverpool timetable updated', 'Northern Rail 2025 timetable now active. Hourly service with additional peak trains. Journey time 45 minutes via Warrington Central.', false, NOW() - INTERVAL '60 days', '6ba7b810-9dad-11d1-80b4-00c04fd430d2'),
  (gen_random_uuid(), 'evt-timetable-activated-003', 'passenger-uuid-006', 'PUSH',  'TIMETABLE_ACTIVATED', 'Manchester–Liverpool timetable updated', 'Northern Rail 2025 timetable now active. Hourly service with additional peak trains. Journey time 45 minutes via Warrington Central.', false, NOW() - INTERVAL '60 days', '6ba7b810-9dad-11d1-80b4-00c04fd430d2'),
  (gen_random_uuid(), 'evt-timetable-activated-003', 'passenger-uuid-007', 'EMAIL', 'TIMETABLE_ACTIVATED', 'Your saved route: Manchester to Liverpool — timetable update', 'Dear passenger, the Northern Rail 2025 timetable is now active for your saved Manchester Piccadilly to Liverpool Lime Street journey.', false, NOW() - INTERVAL '60 days', '6ba7b810-9dad-11d1-80b4-00c04fd430d2'),

  -- TT-007 EMERGENCY notifications (LINE-BHM-EUS — broadcast to all subscribers)
  (gen_random_uuid(), 'evt-emergency-activated-007', 'passenger-uuid-008', 'PUSH',  'EMERGENCY_ACTIVATED', '⚠️ EMERGENCY: Birmingham–London service disrupted', 'URGENT: Track obstruction at Coventry. Birmingham to London Euston services now running via Northampton. Allow extra 45 minutes. Last train from Birmingham 20:00.', true, NOW() - INTERVAL '2 hours', '6ba7b810-9dad-11d1-80b4-00c04fd430dc'),
  (gen_random_uuid(), 'evt-emergency-activated-007', 'passenger-uuid-009', 'PUSH',  'EMERGENCY_ACTIVATED', '⚠️ EMERGENCY: Birmingham–London service disrupted', 'URGENT: Track obstruction at Coventry. Birmingham to London Euston services now running via Northampton. Allow extra 45 minutes. Last train from Birmingham 20:00.', true, NOW() - INTERVAL '2 hours', '6ba7b810-9dad-11d1-80b4-00c04fd430dc'),
  (gen_random_uuid(), 'evt-emergency-activated-007', 'passenger-uuid-010', 'PUSH',  'EMERGENCY_ACTIVATED', '⚠️ EMERGENCY: Birmingham–London service disrupted', 'URGENT: Track obstruction at Coventry. Birmingham to London Euston services now running via Northampton. Allow extra 45 minutes. Last train from Birmingham 20:00.', true, NOW() - INTERVAL '2 hours', '6ba7b810-9dad-11d1-80b4-00c04fd430dc'),
  (gen_random_uuid(), 'evt-emergency-activated-007', 'passenger-uuid-008', 'SMS',   'EMERGENCY_ACTIVATED', 'AVANTI EMERGENCY: BHM-EUS disrupted', 'URGENT Avanti: Track blockage Coventry. Trains via Northampton +45min. Last departure BHM 20:00. Info: 03457 000 125', true, NOW() - INTERVAL '2 hours', '6ba7b810-9dad-11d1-80b4-00c04fd430dc'),
  (gen_random_uuid(), 'evt-emergency-activated-007', 'passenger-uuid-009', 'SMS',   'EMERGENCY_ACTIVATED', 'AVANTI EMERGENCY: BHM-EUS disrupted', 'URGENT Avanti: Track blockage Coventry. Trains via Northampton +45min. Last departure BHM 20:00. Info: 03457 000 125', true, NOW() - INTERVAL '2 hours', '6ba7b810-9dad-11d1-80b4-00c04fd430dc'),
  (gen_random_uuid(), 'evt-emergency-activated-007', 'passenger-uuid-008', 'EMAIL', 'EMERGENCY_ACTIVATED', '⚠️ EMERGENCY ALERT: Your Birmingham to London journey is affected', 'IMPORTANT SERVICE ALERT: A track obstruction at Coventry has caused an emergency timetable to be activated on the Birmingham New Street to London Euston line. Your journey will be diverted via Northampton. Please allow an additional 45 minutes travel time.', true, NOW() - INTERVAL '2 hours', '6ba7b810-9dad-11d1-80b4-00c04fd430dc'),

  -- TT-009 activation notifications (LINE-BRS-PAD)
  (gen_random_uuid(), 'evt-timetable-activated-009', 'passenger-uuid-011', 'PUSH',  'TIMETABLE_ACTIVATED', 'Bristol–London Paddington timetable active', 'GWR 2025 timetable now active. Two trains per hour, express service 1h 45m. First class available.', false, NOW() - INTERVAL '120 days', '6ba7b810-9dad-11d1-80b4-00c04fd430e4'),
  (gen_random_uuid(), 'evt-timetable-activated-009', 'passenger-uuid-012', 'PUSH',  'TIMETABLE_ACTIVATED', 'Bristol–London Paddington timetable active', 'GWR 2025 timetable now active. Two trains per hour, express service 1h 45m. First class available.', false, NOW() - INTERVAL '120 days', '6ba7b810-9dad-11d1-80b4-00c04fd430e4'),
  (gen_random_uuid(), 'evt-timetable-activated-009', 'passenger-uuid-013', 'EMAIL', 'TIMETABLE_ACTIVATED', 'Your saved route: Bristol to London — timetable updated', 'Dear passenger, the GWR 2025 timetable is now active for your saved Bristol Temple Meads to London Paddington journey. First class is available on all services.', false, NOW() - INTERVAL '120 days', '6ba7b810-9dad-11d1-80b4-00c04fd430e4');

-- Processed events
INSERT INTO processed_events (event_id, event_type, processed_at)
VALUES
  ('evt-timetable-activated-001',  'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '30 days'),
  ('evt-timetable-activated-003',  'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '60 days'),
  ('evt-emergency-activated-007',  'EMERGENCY_ACTIVATED',   NOW() - INTERVAL '2 hours'),
  ('evt-timetable-activated-009',  'TIMETABLE_ACTIVATED',   NOW() - INTERVAL '120 days');

COMMIT;
