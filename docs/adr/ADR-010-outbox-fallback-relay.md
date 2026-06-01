# ADR-010: Outbox Fallback Relay for Debezium Outage Resilience

## Status
Accepted

## Context
ADR-001 established the Transactional Outbox pattern with Debezium CDC as the relay mechanism.
Debezium reads the PostgreSQL write-ahead log and publishes committed outbox rows to Kafka. This
provides strong guarantees under normal operating conditions: Debezium is stateful, maintains its
own WAL offset, and resumes exactly where it left off after a restart. However, Debezium's
availability is not guaranteed: connector failures, network partitions between the Debezium worker
and PostgreSQL, or Debezium pod crashes can leave the CDC relay inactive for an extended period.

The outbox table accumulates unpublished rows during a Debezium outage. As long as the outage
duration is shorter than Kafka's configured log retention window, Debezium will catch up when it
recovers and publish all accumulated events without loss. However, if the outage extends beyond the
retention window, events that should have been published but were not will be permanently lost from
the Kafka perspective — even though they remain in the outbox table. More practically, even a
multi-minute Debezium outage degrades system behaviour: downstream consumers (notification-service,
audit-service) stop receiving events, and operators lose live visibility into schedule changes
precisely when they are most likely to need it (during an infrastructure incident).

The existing outbox table schema already has an `unpublished` state concept implicit in the Debezium
CDC model, but no application-layer polling path exists to exploit it. Adding a lightweight scheduled
polling relay directly in timetable-service closes the single-point-of-failure gap without
introducing any new infrastructure dependencies. Consumer-side idempotency (deduplicated on
`eventId` via a `processed_events.event_id UNIQUE` index, established in ADR-001) makes it safe
for both Debezium and the relay to publish the same event: the consumer will process it exactly once
regardless of how many times it arrives.

## Decision
A `@Component` class named `OutboxFallbackRelay` is added to timetable-service. It is annotated
with `@Scheduled(fixedDelayString = "${outbox.relay.delay-ms:300000}")` so the polling interval is
configurable via application properties (default 5 minutes). On each execution the relay:

1. Queries `outbox_events` for rows where `relay_published = false` AND
   `created_at < NOW() - INTERVAL '5 minutes'` (the 5-minute grace window prevents the relay and
   Debezium from racing on newly inserted rows that Debezium would normally publish within seconds).
2. Publishes each qualifying row via a dedicated `relayKafkaTemplate` bean. This template is
   deliberately configured as **non-transactional** (no `transactional-id-prefix`) to keep it
   completely isolated from the main transactional `KafkaTemplate` used by schedule-service and
   distribution-service. Mixing transactional and non-transactional producers on the same topic is
   supported by Kafka but the relay's non-transactional producer must not be allowed to interfere
   with the exactly-once semantics of the primary producers.
3. Marks each successfully published row with `relay_published = true` in a separate `UPDATE`
   statement (not in the same transaction as the publish, because Kafka publish is not transactional
   here). If the relay crashes between publish and update, the row will be re-published on the next
   cycle — consumer idempotency makes this safe.
4. Logs a `WARN`-level message for every batch of rows found, so a non-zero relay execution is
   immediately visible in the log aggregation system and can be used as an alert condition indicating
   that Debezium may be lagging or offline.

The `relay_published` boolean column is added to `outbox_events` by Flyway migration `V3__add_relay_published_flag.sql`. A partial index `ON outbox_events (created_at) WHERE relay_published = false` is included in the same migration to keep the polling query efficient as the table grows.

## Consequences

### Positive
- Belt-and-suspenders delivery guarantee: a Debezium outage lasting less than the relay polling
  interval (5 minutes, configurable) is fully self-healing with no operator intervention.
- The relay is entirely contained within the existing timetable-service process with no new
  infrastructure components. It depends only on PostgreSQL (already required) and Kafka (already
  required).
- Consumer idempotency (ADR-001) makes duplicate delivery from concurrent Debezium and relay
  publication safe by design — no additional consumer-side changes are required.
- The `WARN` log on relay activity doubles as a passive Debezium health signal: if relay logs appear
  regularly in production, it is a leading indicator that Debezium is not keeping up.
- The configurable `outbox.relay.delay-ms` property allows the interval to be tightened in
  production without a code deployment if the tolerance for Debezium-outage-induced event lag
  needs to change.

### Negative / Trade-offs
- The relay introduces a maximum event delay of 5 minutes for the fallback path. Events that miss
  Debezium publication will not be re-published for up to 5 minutes after they become eligible
  (i.e., 10 minutes after `created_at`). This is acceptable for the platform's ≤5 s p95 target on
  the happy path, but the fallback path explicitly does not meet that SLO.
- The `relay_published = true` update is not atomic with the Kafka publish. A crash between publish
  and update causes the event to be published twice on the next relay cycle. All consumers must
  have robust idempotency — this is a pre-existing requirement from ADR-001 but is now load-bearing
  for the relay path as well.
- The `relayKafkaTemplate` is a separate producer and has its own circuit breaker configuration
  requirement (see ADR-009). If the relay producer is not monitored separately, a failing relay
  Kafka producer could silently stop performing its fallback role.
- Flyway migration `V3` adds a column to `outbox_events` and creates a new partial index. The
  migration must be applied before the relay code is deployed. Deployment order (migration first,
  then app) must be enforced in `cd-deploy.yml`.

## Alternatives Considered

| Alternative | Reason Rejected |
|---|---|
| Debezium HA (multiple connectors, distributed mode) | Reduces Debezium SPOF risk but does not eliminate it; adds significant Kafka Connect operational complexity; does not cover the case where the PostgreSQL WAL position itself is lost |
| Polling publisher as primary mechanism (no Debezium) | Removes Debezium entirely; avoids the CDC dependency but sacrifices sub-second event latency (polling minimum is seconds), increases PostgreSQL read load, and loses Debezium's WAL-based exactly-once relay guarantee |
| AWS EventBridge as fallback relay | Introduces a second event bus and a cross-system integration contract; significantly more complex to test and reason about; out of scope for Phase 8 |
| Manual operator replay from outbox | Requires human intervention during every Debezium outage; violates the goal of self-healing infrastructure for common failure modes |
