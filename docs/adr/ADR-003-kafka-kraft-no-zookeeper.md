# ADR-003: Kafka in KRaft Mode (No ZooKeeper)

## Status
Accepted

## Context
Kafka 4.x removed ZooKeeper entirely. KRaft (Kafka Raft Metadata) is now the only supported metadata mode. The system targets Kafka 4.3.0.

## Decision
Run Kafka in KRaft mode. No ZooKeeper infrastructure is provisioned. The `KAFKA_PROCESS_ROLES` environment variable configures each broker as `broker,controller` in development and as separate `broker` / `controller` roles in production.

## Consequences
**Positive:**
- Simpler infrastructure (one fewer distributed system to operate).
- Faster controller failover (no ZooKeeper session timeout).
- Better supported: ZooKeeper mode is no longer available in Kafka 4.x.

**Negative:**
- Some operational tooling written for ZooKeeper mode (older Kafka UIs) may not support KRaft fully — verify tooling compatibility before adopting.

## Alternatives Considered
- ZooKeeper mode: not available in Kafka 4.x.
