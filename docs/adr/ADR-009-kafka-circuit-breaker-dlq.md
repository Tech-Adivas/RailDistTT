# ADR-009: Kafka Circuit Breaker and Dead Letter Queue Strategy

## Status
Accepted

## Context
Two microservices — schedule-service and distribution-service — act as Kafka producers on the
critical path of incoming HTTP requests. If the Kafka brokers are briefly unavailable (rolling
restart, network partition, leader election), an unprotected `kafkaTemplate.send()` call will block
or throw an exception that propagates directly to the HTTP caller. Without a circuit breaker, every
in-flight request during a Kafka outage will accumulate in the thread pool, potentially exhausting
available threads and causing a full service cascade failure across services that have nothing to do
with Kafka.

On the consumer side, every service that reads from Kafka topics is vulnerable to the
"poison message" problem: a single malformed, schema-incompatible, or permanently unprocessable
message can cause a consumer to throw an exception on every poll attempt. Without a dead-letter
mechanism, the consumer's offset never advances, the consumer lag grows unboundedly, and all
messages behind the bad one are effectively blocked — including messages that would have been
processed successfully. This is a critical operational risk given that Avro schema evolution
mistakes (see ADR-004) or upstream data quality issues can introduce poison messages at any time.

The platform already uses Resilience4j for HTTP-to-HTTP circuit breaking (see service inter-
communication standards). Extending its circuit breaker pattern to Kafka producers is consistent
with the existing resilience approach and avoids introducing a second circuit breaker library.
Spring Kafka's `DefaultErrorHandler` provides the dead-letter publishing infrastructure natively
without additional dependencies.

## Decision
**Producer resilience:** Every `kafkaTemplate.send()` call in schedule-service and
distribution-service is wrapped with a Resilience4j `@CircuitBreaker` annotation (or programmatic
equivalent) named `kafka-<service>-producer`. The circuit breaker is configured with a sliding-
window of 10 calls and opens when the failure rate reaches 50% within that window. Once open, the
circuit remains open for 30 seconds before transitioning to half-open. When the circuit is open,
the method throws `KafkaProducerCircuitOpenException` — the calling request handler catches this
and routes the event to the Transactional Outbox (ADR-001) as a fallback, ensuring the event is
not lost even when Kafka is unavailable.

Producer `KafkaTemplate` is hardened with: `retries=10`, `max.in.flight.requests.per.connection=1`
(preserves message ordering during retries), `acks=all` (waits for all in-sync replica
acknowledgements), and `enable.idempotence=true` (prevents duplicate records due to producer
retries). These settings together provide at-most-once producer ordering and at-least-once delivery.

**Consumer resilience:** Every Kafka listener container is configured with Spring Kafka's
`DefaultErrorHandler` using a `FixedBackOff`-equivalent exponential backoff: 3 retry attempts at
1 s, 2 s, and 4 s delays before declaring a message unrecoverable. After all retries are exhausted,
the `DeadLetterPublishingRecoverer` routes the failed message to the `railway.dlq` topic,
preserving the original message payload and all original headers (including the topic of origin,
partition, offset, and exception details in `kafka_dlt_*` headers). DLQ consumers are separate
consumer groups dedicated to monitoring and triage; they do not auto-process messages.

## Consequences

### Positive
- A single poison message cannot block all consumers on a topic; after 3 retries + backoff the
  message is side-lined to the DLQ and the main consumer advances past it.
- Transient Kafka producer failures during brief broker unavailability (rolling restarts, leader
  elections) do not cascade into HTTP request failures; the circuit breaker absorbs the failure and
  the outbox provides a durable fallback path.
- The DLQ preserves the original message verbatim with full diagnostic headers, giving on-call
  engineers the information needed to diagnose and manually re-drive or discard the message.
- Idempotent producer settings (`enable.idempotence=true`, `acks=all`) eliminate broker-side
  duplicate records from producer retries, complementing the consumer-side idempotency layer.
- Resilience4j circuit breaker metrics are exposed via Micrometer and visible in the Grafana
  dashboard, making circuit state observable in production.

### Negative / Trade-offs
- The DLQ is not self-healing: messages that land in `railway.dlq` require human triage. A
  monitoring alert must fire when the DLQ consumer lag exceeds zero, and a runbook
  (`docs/runbooks/dlq-triage.md`) must be maintained with replay and discard procedures.
- The circuit breaker introduces a latency spike on the first few requests after the circuit
  transitions from open to half-open (probe requests): callers that hit the circuit during half-open
  may still receive a `KafkaProducerCircuitOpenException` if the probe fails.
- The `DeadLetterPublishingRecoverer` must use a separate, non-transactional `KafkaTemplate` to
  publish to the DLQ topic. Using the main transactional template for DLQ publication would pollute
  the main transaction and can cause DLQ publish failures to roll back the original transaction —
  the opposite of the intended behaviour.
- `max.in.flight.requests.per.connection=1` reduces producer throughput compared to the default of
  5 in-flight requests. This is acceptable at current scale but should be revisited if sustained
  producer throughput requirements increase significantly.

## Alternatives Considered

| Alternative | Reason Rejected |
|---|---|
| Infinite consumer retry without DLQ | Unacceptable: a single poison message permanently blocks all consumer progress on the affected partition |
| No error handling (let exceptions propagate) | Unacceptable: container-managed exception propagation restarts the listener, recreating the same failure loop |
| Kafka Streams error topic (via Kafka Streams DSL) | Over-engineered for the current use case; the platform uses plain `@KafkaListener` consumers, not Kafka Streams topologies |
| Separate retry topics (retry-1, retry-2, retry-3) | Common pattern but adds topic management overhead; Spring Kafka's in-process exponential backoff achieves the same goal with zero extra topics for the retry phase |
| Separate circuit breaker library (Hystrix, Sentinel) | Hystrix is no longer maintained; Resilience4j is already the platform standard; no reason to introduce a second library |
