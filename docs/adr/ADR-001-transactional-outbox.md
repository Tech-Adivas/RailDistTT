# ADR-001: Transactional Outbox Pattern for Event Publication

## Status
Accepted

## Context
The system must guarantee that every acknowledged database write produces exactly one Kafka event — no phantom events (event without state change) and no missed events (state change without event). A naive dual-write (write DB, then write Kafka) violates this guarantee because the two operations are not atomic: a crash between them leaves the system inconsistent.

## Decision
Use the **Transactional Outbox** pattern:
1. State changes and an outbox record are committed atomically in a single PostgreSQL transaction.
2. A **Debezium** CDC connector reads the PostgreSQL WAL and publishes outbox rows to Kafka as events.
3. Debezium maintains its own offset (WAL position), so it resumes exactly where it left off after a crash — no event loss, no duplication in the relay itself.
4. Consumers deduplicate on `eventId` (idempotent) because Kafka delivers at-least-once.

## Consequences
**Positive:**
- Zero acknowledged-write loss — both state and event commit or neither does.
- No phantom events — Debezium only reads committed rows.
- Simple application code — the service only needs one transaction with one extra INSERT.
- Consumers are naturally safe for replays (idempotency layer).

**Negative:**
- Requires Debezium as an additional infrastructure component.
- PostgreSQL must have `wal_level = logical` enabled.
- Debezium's lag (typically < 500 ms) means events are not synchronously published — acceptable for the ≤5 s p95 target.

## Alternatives Considered
- **Dual-write with saga**: Complex, hard to reason about, still has race conditions under crash.
- **Kafka Transactions only**: Does not solve the DB ↔ Kafka atomicity gap.
- **Polling publisher**: Simpler than CDC but higher latency and more DB load.
