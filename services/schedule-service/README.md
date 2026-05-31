# services/schedule-service

## Purpose

Stateless computation service. Consumes `TimetableChangedEvent` and `MaintenanceWindowEvent` from Kafka, computes effective schedules (resolving conflicts, applying maintenance windows, generating stop times), and emits `ScheduleComputedEvent`.

Idempotent consumer: duplicate delivery of the same `eventId` is a no-op (deduplication via unique DB constraint on `processed_events`).

## Architecture

```mermaid
flowchart LR
  K{{Kafka}} -->|TimetableChangedEvent| C[Consumer\nIdempotent]
  K -->|MaintenanceWindowEvent| C
  C --> DUP{eventId\nalready seen?}
  DUP -->|yes| SKIP[Skip — no-op]
  DUP -->|no| COMPUTE[Schedule Computation Engine]
  COMPUTE --> EMIT[Produce ScheduleComputedEvent]
  EMIT --> K
  C --> RETRY{Transient\nerror?}
  RETRY -->|yes| BACKOFF[Exponential backoff retry]
  RETRY -->|no — poison| DLQ[(Dead Letter Queue)]
```

## Tech & Versions

| Component | Version |
|-----------|---------|
| Spring Boot | 4.0.6 |
| Spring Kafka | via Boot BOM |
| events | 1.0.0-SNAPSHOT |

## Error Handling

| Scenario | Detection | Response |
|----------|-----------|---------|
| Duplicate event | `eventId` in `processed_events` | Skip silently — idempotent |
| Transient error | Exception during computation | Retry up to 3× with exponential backoff |
| Poison message | Deserialization failure / invariant violation | Route to DLQ; alert fires |
| Kafka unavailable | Producer exception | Retry with backoff; DLQ on exhaustion |

## Testing

```bash
./mvnw verify -pl services/schedule-service -Dgroups=integration
```
