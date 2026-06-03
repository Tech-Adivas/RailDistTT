# Seed Data — Railway Timetable Distribution Platform

Sample/seed data for all five service databases. Uses realistic UK railway data with consistent UUIDs across all services.

## What is seeded

| File | Target database | Service | Contents |
|---|---|---|---|
| `01-timetable-db.sql` | `timetable_db` | timetable-service (8081) | 10 timetables (all 8 statuses), full audit log, track segments, maintenance windows |
| `02-schedule-db.sql` | `schedule_db` | schedule-service (8082) | 5 computed schedules (ACTIVE, APPROVED, EMERGENCY_ACTIVE), idempotency records |
| `03-query-db.sql` | `query_db` | query-service (8083) | CQRS read model projection of all 10 timetables, schedule read model, idempotency records |
| `04-distribution-db.sql` | `distribution_db` | distribution-service (8084) | Distribution tracking for 4 fan-out channels per active timetable (with one deliberate FAILED record), idempotency records |
| `05-notification-db.sql` | `notification_db` | notification-service (8085) | PUSH, SMS, and EMAIL notifications for all activation and emergency events, idempotency records |

## Timetables seeded

| UUID suffix | Line | Status | Notes |
|---|---|---|---|
| `...0001` | LINE-VIC-BRI | ACTIVE | Southern Rail Summer 2025 — Victoria to Brighton |
| `...0002` | LINE-VIC-BRI | APPROVED | Autumn/Winter 2025 — future successor to TT-001 |
| `...0003` | LINE-MCR-LIV | ACTIVE | Northern Rail 2025 — Manchester to Liverpool |
| `...0004` | LINE-MCR-LIV | PENDING_REVIEW | Northern Rail 2026 Q1 — awaiting approval |
| `...0005` | LINE-EDI-GLA | DRAFT | ScotRail 2026 — work in progress |
| `...0006` | LINE-EDI-GLA | REJECTED | ScotRail 2025 H2 — rejected due to CrossCountry conflicts |
| `...0007` | LINE-BHM-EUS | EMERGENCY_ACTIVE | Avanti emergency — track obstruction at Coventry |
| `...0008` | LINE-BHM-EUS | SUPERSEDED | Avanti standard service — auto-superseded by TT-007 |
| `...0009` | LINE-BRS-PAD | ACTIVE | GWR 2025 — Bristol to London Paddington |
| `...000a` | LINE-BRS-PAD | CANCELLED | GWR 2025 H2 — cancelled, infrastructure investment deferred |

All timetable UUIDs share the prefix `550e8400-e29b-41d4-a716-44665544000*` and are consistent across all five databases.

## How to run

### All databases at once (recommended)

Requires the infra stack to be running (`make up`):

```bash
make seed-data
```

### Individual database

```bash
docker compose exec -T postgres psql -U railway -d timetable_db    < infra/postgres/seed/01-timetable-db.sql
docker compose exec -T postgres psql -U railway -d schedule_db     < infra/postgres/seed/02-schedule-db.sql
docker compose exec -T postgres psql -U railway -d query_db        < infra/postgres/seed/03-query-db.sql
docker compose exec -T postgres psql -U railway -d distribution_db < infra/postgres/seed/04-distribution-db.sql
docker compose exec -T postgres psql -U railway -d notification_db < infra/postgres/seed/05-notification-db.sql
```

### Reset and re-seed

To wipe all data and re-seed from scratch:

```bash
make clean   # stops containers and removes volumes
make up      # recreates databases via infra/postgres/init/
make seed-data
```

## Idempotency

Each script guards against double-seeding with a `DO $$ BEGIN ... END $$` check at the top of the transaction. Running a script a second time prints `NOTICE: Sample data already loaded — skipping.` and exits cleanly without modifying any data.

## Notable scenarios in the data

- **Emergency activation flow** — TT-007 (`EMERGENCY_ACTIVE`) was activated 2 hours ago by `user-michael-chen`, which simultaneously superseded TT-008. The `audit_log`, `distribution_tracking`, and `sent_notifications` tables all share the same `correlation_id` (`6ba7b810-...-00c04fd430dc`) for end-to-end tracing.
- **Failed distribution** — TT-003's `PARTNER_FEED` distribution has status `FAILED` with a realistic timeout error message, useful for testing the DLQ triage runbook.
- **Rejection with changes requested** — TT-006 went through two review cycles (CHANGES_REQUESTED then REJECTED) with full justification text in the audit log.
- **Maintenance window causing emergency** — The active `EMERGENCY` maintenance window on `SEG-BHM-COV` is the root cause of the TT-007 emergency activation.
