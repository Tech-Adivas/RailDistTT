# ADR-002: CQRS with Separate Write and Read Models

## Status
Accepted

## Context
The timetable platform has two very different access patterns: complex, constraint-heavy writes (approval workflow, optimistic locking, audit) and high-volume, low-latency reads (passenger apps, station displays). Serving both from the same model creates contention and couples the schema to the worst of both worlds.

## Decision
Use **CQRS** with separate write and read models:
- **Write model** (Timetable Service): normalised PostgreSQL schema optimised for invariant enforcement, optimistic locking, and transactional integrity.
- **Read model** (Query Service): denormalised PostgreSQL schema + Redis cache, built by the Query Projector consuming Kafka events. Optimised for fast, filtered reads.
- **Projector** rebuilds the read model from event replay — making it a recoverable derived dataset, not a source of truth.

## Consequences
**Positive:**
- Write path is clean, consistent, and strongly typed.
- Read path can be scaled independently and tuned for query patterns.
- Read model can be rebuilt from scratch by replaying Kafka events (7-day retention).
- Cache layer reduces DB load on read path.

**Negative:**
- Eventual consistency: reads may lag writes by up to a few seconds.
- More moving parts to operate (projector, cache, two schemas).
- Two codebases to maintain for related functionality.

## Alternatives Considered
- **Shared model, read replicas**: Simpler but couples schemas and prevents independent scaling.
- **GraphQL federation**: Adds complexity without solving the core write/read model mismatch.
