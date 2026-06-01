# Runbook: Dead Letter Queue (DLQ) Triage

**Topic:** `railway.dlq`
**Alert:** `DLQMessagesPresent`
**Severity:** WARNING
**Last updated:** 2026-06-01

---

## 1. Overview

The Dead Letter Queue (DLQ) is the terminal destination for Kafka messages that could not be successfully processed by any consumer after all retry attempts have been exhausted.

Every service in the platform uses `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer`. When a consumer throws an exception, the message is retried up to 3 times with exponential back-off (1 s → 2 s → 4 s, max 10 s per interval). If all 3 attempts fail, the recoverer publishes the original record to `railway.dlq` and commits the consumer offset — preventing the consumer from being stuck on a poison message.

The `DeadLetterPublishingRecoverer` writes the following headers on each DLQ record:

| Header | Content |
|---|---|
| `kafka_dlt-original-topic` | The topic the message was originally consumed from |
| `kafka_dlt-original-partition` | The partition number |
| `kafka_dlt-original-offset` | The offset of the failed record |
| `kafka_dlt-exception-fqcn` | The fully qualified class name of the exception |
| `kafka_dlt-exception-message` | The exception message string |
| `kafka_dlt-original-consumer-group` | The consumer group that failed to process it |

Certain non-retryable exceptions (e.g. `SerializationException`, `IllegalArgumentException`, `IllegalStateException`) bypass the retry loop entirely and route directly to the DLQ to prevent reprocessing poison messages.

**What the DLQ is not:** The DLQ does not represent data loss. Every timetable write uses the Transactional Outbox pattern — the original state change is committed in the source database before any Kafka event is published. A message in the DLQ means a downstream consumer failed to process a fully-persisted event.

### Alert: `DLQMessagesPresent`

```yaml
alert: DLQMessagesPresent
expr: kafka_topic_partitions_messages_in_rate{topic=~".*dlq.*"} > 0
for: 1m
severity: warning
```

This alert fires when any message has arrived in the DLQ topic within the last minute. It will resolve automatically once the DLQ ingestion rate returns to zero.

---

## 2. Severity

**WARNING** — DLQ presence alone does not breach any SLO.

- The read-availability SLO (99.9%) is measured on HTTP responses from `query-service` and `api-gateway`. DLQ messages affect downstream data freshness, not query availability.
- A sustained DLQ rate (>10 messages/minute for 5+ consecutive minutes) is an escalation trigger — see Section 7.

Treat the alert as a signal to investigate within the current business day unless the rate is high or the original topic is `railway.timetable.changed` (which would mean timetable state changes are not reaching downstream consumers).

---

## 3. Triage Steps

### Step 1 — Confirm the alert and check DLQ depth

Open the **Grafana kafka-consumer-lag dashboard** and navigate to the DLQ topic panel. Note:
- Which topic partition(s) have accumulating messages
- The rate of incoming messages (messages/min)
- Whether the rate is increasing, stable, or decreasing

### Step 2 — Identify the consumer group and original topic

```bash
# List all consumer groups in the namespace
kubectl -n railway-platform exec -it deploy/timetable-service -- \
  kafka-consumer-groups.sh \
  --bootstrap-server $KAFKA_BROKERS \
  --list

# Describe a specific group to find lag and partition assignments
kubectl -n railway-platform exec -it deploy/timetable-service -- \
  kafka-consumer-groups.sh \
  --bootstrap-server $KAFKA_BROKERS \
  --describe \
  --group <group-id>
```

Known consumer group IDs:

| Service | Consumer Group |
|---|---|
| schedule-service | `schedule-service-group` |
| query-service | `query-service-group` |
| distribution-service | `distribution-service-group` |
| notification-service | `notification-service-group` |

### Step 3 — Inspect DLQ message headers

Use the Kafka console consumer to read a sample of DLQ messages and inspect headers:

```bash
kubectl -n railway-platform exec -it deploy/timetable-service -- \
  kafka-console-consumer.sh \
  --bootstrap-server $KAFKA_BROKERS \
  --topic railway.dlq \
  --from-beginning \
  --max-messages 5 \
  --property print.headers=true \
  --property print.offset=true \
  --property print.partition=true
```

Look for the `kafka_dlt-original-topic`, `kafka_dlt-exception-fqcn`, and `kafka_dlt-exception-message` headers. These are the primary indicators for determining root cause.

### Step 4 — Check service logs for the exception

Once you know which service and exception class is involved, pull recent logs:

```bash
# Current pod logs
kubectl logs -n railway-platform deploy/<service-name> --tail=200

# Previous pod logs (if pod restarted)
kubectl logs -n railway-platform deploy/<service-name> --previous

# Search for the specific exception class
kubectl logs -n railway-platform deploy/<service-name> --tail=500 \
  | grep -i "DLQ\|dead.letter\|<ExceptionClassName>"
```

### Step 5 — Common root causes

| `kafka_dlt-exception-fqcn` | Likely cause | Resolution path |
|---|---|---|
| `org.apache.kafka.common.errors.SerializationException` or `io.confluent.kafka.schemaregistry.*` | Avro schema mismatch — producer published with a schema version the consumer cannot deserialise | Section 4.1 |
| `java.lang.NullPointerException` | Bug in consumer processing logic | Fix the consumer, re-deploy, then replay (Section 5) |
| `org.springframework.dao.DataIntegrityViolationException` | DB constraint violation — duplicate key or FK violation | Check for data inconsistency; see Section 4.3 |
| `org.springframework.dao.CannotAcquireLockException` | DB lock contention during high load or failover | If transient, replay after DB recovers (Section 4.2) |
| `java.net.SocketTimeoutException` or `io.github.resilience4j.*` | Downstream service call timed out | Check target service health, then replay (Section 4.2) |
| `java.lang.IllegalArgumentException` or `IllegalStateException` | Unexpected or malformed message payload | Treat as poison message (Section 4.4) |

---

## 4. Resolution Paths

### 4.1 Schema mismatch (Avro incompatibility)

**Symptoms:** `SerializationException` in headers; messages arrive after a service deployment.

1. Identify which producer service published the bad schema version by correlating the `kafka_dlt-original-topic` with the service that publishes to that topic.
2. Check the Schema Registry for recent schema version changes:
   ```bash
   curl http://<schema-registry>:8081/subjects/<topic>-value/versions
   curl http://<schema-registry>:8081/subjects/<topic>-value/versions/<version>
   ```
3. If the new schema is not BACKWARD compatible, roll back the producer service:
   ```bash
   helm rollback <producer-service> <previous-revision> -n railway-platform
   ```
4. After rollback, replay the DLQ messages (Section 5). The consumer should now be able to deserialise them with the previous schema.
5. Fix the schema to be BACKWARD compatible and re-deploy through the standard process.

### 4.2 Transient failures (DB unavailable, downstream timeout)

**Symptoms:** `CannotAcquireLockException`, `DataAccessException`, or connection timeout exceptions. Failures occurred during a specific time window and have since stopped.

1. Confirm that the root cause (DB failover, service restart) has resolved.
2. Verify the affected service is now healthy: `kubectl -n railway-platform get pods`.
3. Replay the DLQ messages using the procedure in Section 5.

### 4.3 DB constraint violation

**Symptoms:** `DataIntegrityViolationException`. Message may have been partially processed before failing.

1. Check the affected service's database for the record in question using the `kafka_dlt-original-topic` and offset to determine the event type and aggregate ID.
2. If the constraint was hit because the record already exists (duplicate), the consumer's idempotency check may have failed to fire. Investigate the `processed_events` table:
   ```sql
   SELECT * FROM processed_events WHERE event_id = '<event-id-from-message>';
   ```
3. If the event is already recorded as processed, the message is a safe duplicate — discard with an audit log entry (Section 4.4).
4. If the event is NOT in `processed_events` but the downstream record exists, there is a data inconsistency. Escalate to the on-call engineer.

### 4.4 Poison message (permanently unprocessable / bad data)

**Symptoms:** `IllegalArgumentException`, `IllegalStateException`, or a domain validation exception that will never succeed regardless of retries.

Poison messages must be explicitly discarded. Do not replay them — they will re-enter the DLQ indefinitely.

1. Read the full message content and headers. Screenshot or save them for the audit record.
2. Write an audit log entry. At minimum record:
   - Timestamp
   - DLQ topic, partition, and offset
   - Original topic, partition, and original offset
   - Exception class and message
   - Your name and justification for discarding
   - Ticket/incident reference
3. The message is acknowledged by the fact that it was consumed from the DLQ by the console consumer above. No further action is needed to prevent reprocessing — do NOT replay this message.
4. If the poison message originated from a producer publishing invalid data, create a bug report against that service. Schema registry enforcement (Section 6) should prevent recurrence.

---

## 5. Replay Procedure

Use this procedure when a batch of DLQ messages arose from a transient, now-resolved failure and the consumer can process them successfully today.

> **Warning:** Replaying messages means re-consuming them by the original consumer group. Ensure consumers are idempotent (all platform consumers check `processed_events.event_id`) before replaying.

### Option A — Seek the consumer group back on the original topic (preferred)

This replays from the original topic rather than the DLQ, which preserves message ordering.

1. Stop or scale down the consumer to avoid concurrent processing:
   ```bash
   kubectl -n railway-platform scale deployment/<service-name> --replicas=0
   ```
2. Identify the earliest failed offset from the DLQ headers.
3. Reset the consumer group offset to that position:
   ```bash
   kubectl -n railway-platform exec -it deploy/timetable-service -- \
     kafka-consumer-groups.sh \
     --bootstrap-server $KAFKA_BROKERS \
     --group <group-id> \
     --topic <original-topic>:<partition>:<offset> \
     --reset-offsets \
     --to-offset <target-offset> \
     --execute
   ```
4. Scale the consumer back up:
   ```bash
   kubectl -n railway-platform scale deployment/<service-name> --replicas=<original-count>
   ```
5. Watch the consumer lag drop on the Grafana kafka-consumer-lag dashboard. Verify no new DLQ messages appear.

### Option B — Re-publish DLQ messages to the original topic

Use this when Option A is not viable (e.g. original topic retention has expired).

1. Write a one-off consumer that reads from `railway.dlq`, filters messages by the `kafka_dlt-original-topic` header for your target topic, and re-publishes to that original topic.
2. Coordinate with the team before executing — this is a manual operation with risk of data duplication if idempotency is broken.

---

## 6. Prevention

- **Schema Registry compatibility checks:** All Avro schema changes must pass `BACKWARD` compatibility validation enforced in CI via the Schema Registry Maven plugin. PRs that introduce incompatible schemas are blocked. See `ADR-004-avro-backward-compatibility.md`.
- **Integration tests for error paths:** Each service has integration tests (Testcontainers) that verify the DLQ routing path — a test message that triggers a `DefaultErrorHandler`-handled exception must appear on the DLQ topic.
- **Non-retryable exception list:** Review `KafkaConsumerConfig.errorHandler()` in each service. If a new exception class should go to DLQ immediately (not retry), add it to `handler.addNotRetryableExceptions(...)`.
- **Idempotency on all consumers:** Before replaying any messages, verify the `processed_events` table is in place for the target service. Never disable idempotency checks.

---

## 7. Escalation

Page the on-call engineer when **any** of the following are true:

- DLQ ingestion rate exceeds **10 messages/minute sustained for 5+ consecutive minutes**
- DLQ messages originate from `railway.timetable.changed` (state change events not reaching the read model or distribution layer — passenger-facing impact)
- DLQ messages originate after an emergency activation (EMERGENCY_ACTIVE timetable not distributed)
- Root cause cannot be identified within 30 minutes of initial alert

On-call escalation path: PagerDuty service `railway-platform-oncall` → Platform Engineering team.
