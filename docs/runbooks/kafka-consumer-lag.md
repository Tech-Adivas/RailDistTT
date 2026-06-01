# Runbook: Kafka Consumer Lag

**Alerts:** `KafkaConsumerLagHigh` (WARNING) / `KafkaConsumerLagCritical` (CRITICAL)
**Dashboard:** Grafana → kafka-consumer-lag
**Last updated:** 2026-06-01

---

## 1. Overview

Kafka consumer lag is the number of messages that have been written to a topic partition but not yet consumed and committed by a consumer group. Lag is a leading indicator of processing health: a healthy platform has near-zero steady-state lag as consumers keep pace with producers.

**Expected steady-state lag:** < 100 messages per consumer group, across all partitions.

Lag spikes are normal and transient during deployments (rolling restart) or short GC pauses. Sustained lag — especially lag that continues to grow — indicates a consumer that is stuck, unhealthy, or unable to process at the rate required.

### Consumer groups to monitor

| Consumer Group | Service | Consumes from |
|---|---|---|
| `schedule-service-group` | schedule-service | `railway.timetable.changed`, `railway.maintenance.windows` |
| `query-service-group` | query-service | `railway.timetable.changed`, `railway.schedule.computed` |
| `distribution-service-group` | distribution-service | `railway.schedule.computed` |
| `notification-service-group` | notification-service | `railway.notification.requests` |

---

## 2. Alert Thresholds

Both alerts are defined in `infra/observability/alert-rules/slo-alerts.yml`:

```yaml
# WARNING — investigate promptly
alert: KafkaConsumerLagHigh
expr: kafka_consumer_group_lag > 1000
for: 5m
severity: warning

# CRITICAL — act immediately; message processing has likely stalled
alert: KafkaConsumerLagCritical
expr: kafka_consumer_group_lag > 10000
for: 2m
severity: critical
```

A `KafkaConsumerLagCritical` alert means the consumer group has fallen more than 10,000 messages behind for at least 2 minutes. At typical platform throughput this represents several minutes to tens of minutes of backlog and will cause visible data staleness on query results and passenger displays.

**Lag recovery SLO:** After resolving the root cause, consumer lag should return to < 100 within **15 minutes**.

---

## 3. Immediate Diagnosis

Work through these steps in order. The first step that reveals a definitive cause is usually sufficient — you do not need to complete all steps before beginning remediation.

### Step 1 — Open the Grafana kafka-consumer-lag dashboard

Navigate to Grafana → **kafka-consumer-lag** dashboard.

Identify:
- Which consumer group has the elevated lag (check all groups listed in Section 1)
- Which topic and partition(s) the lag is on
- Whether the lag is growing, flat, or starting to recover
- The approximate time the lag started — correlate with recent deployments or alerts

### Step 2 — Check pod health

```bash
kubectl -n railway-platform get pods
```

Look for pods in `CrashLoopBackOff`, `Error`, `OOMKilled`, or `Pending` state. A crashlooping consumer cannot commit offsets, causing lag to grow.

If pods are restarting, jump to Section 4.1.

```bash
# Watch pod status live during investigation
kubectl -n railway-platform get pods -w
```

### Step 3 — Describe the consumer group

```bash
kubectl -n railway-platform exec -it deploy/timetable-service -- \
  kafka-consumer-groups.sh \
  --bootstrap-server $KAFKA_BROKERS \
  --describe \
  --group <group-id>
```

In the output, look for:
- **LAG** column — partitions with zero consumer ID in the HOST column are unassigned (no consumer is processing them)
- **CONSUMER-ID** — if blank, the partition has no active consumer
- Uneven lag distribution — one partition with very high lag may indicate a stuck consumer thread or hot partition

### Step 4 — Check JVM heap on the affected service

Open Grafana → **jvm-overview** dashboard. Filter by the affected service name.

Look for:
- Heap usage approaching or exceeding 85% (this will also trigger `JvmHeapUsageHigh` alert)
- Frequent full GCs causing stop-the-world pauses longer than `max.poll.interval.ms` (300 s) — this causes consumers to be kicked out of the group, triggering a rebalance

If heap is high, jump to Section 4.3 (processing too slow / need to scale).

### Step 5 — Check DB connection pool

Open Grafana → **timetable-service** dashboard (or the equivalent for the affected service). Look at the **HikariCP** panels:

- **Active connections** approaching `maximum-pool-size` (20) means the pool is exhausted
- **Connection wait time** elevated means consumers are blocked waiting for a DB connection
- **Pending threads** > 0 is a direct indicator of pool exhaustion

If HikariCP is saturated, the DB (RDS) is likely too slow — see Section 4.2.

---

## 4. Common Causes and Fixes

### 4.1 Consumer crashed or is restarting

**Symptoms:** Pod in `CrashLoopBackOff` or recently restarted; lag growing for the duration of the crash loop.

```bash
# Check logs from the crashed container
kubectl logs -n railway-platform deploy/<service-name> --previous

# Follow current logs to watch for repeated failures
kubectl logs -n railway-platform deploy/<service-name> --follow --tail=100
```

Common crash causes:
- **OOMKilled:** Increase memory limit in Helm values (`resources.limits.memory`) or investigate a memory leak
- **Startup failure / Flyway migration error:** Check logs for `FlywayException` or database connection errors on startup
- **Vault secret not found:** Check logs for `VaultResponseException` — the Vault Agent sidecar may not have injected secrets yet (wait for pod to be fully ready before the app starts)

After identifying the crash cause and fixing it, the pod will restart and the consumer will resume from its last committed offset. Lag should start dropping within 1-2 minutes of the pod becoming Ready.

### 4.2 Database too slow

**Symptoms:** HikariCP pool exhausted; RDS CloudWatch showing high CPU, high read latency, or IOPS throttling.

1. Check RDS CloudWatch metrics for the affected service's database instance:
   - `CPUUtilization` > 80%
   - `DatabaseConnections` approaching instance limit
   - `ReadLatency` / `WriteLatency` elevated
   - `FreeStorageSpace` critically low (can cause write failures)

2. If RDS is under high read load, consider routing read queries to the read replica (requires code change — create a ticket).

3. If RDS is under high write load from other sources (e.g. a bulk backfill), coordinate with that team to throttle the writes.

4. If the database is in a failover or degraded state, refer to the `database-failover.md` runbook.

### 4.3 Processing too slow — scale the consumer

**Symptoms:** Consumer is healthy and connected, DB is healthy, but lag keeps growing because the consumer cannot keep up with the producer rate.

**Option A — Increase consumer concurrency (Helm values, no code change required):**

Each service's `ConcurrentKafkaListenerContainerFactory` is configured with `setConcurrency(3)`. This can be increased up to the number of partitions on the topic.

```bash
# Increase concurrency via Helm upgrade (example: bump to 6)
helm upgrade --install <service-name> infra/helm/<service-name> \
  -n railway-platform \
  --set kafkaConsumerConcurrency=6 \
  -f infra/helm/<service-name>/values-prod.yaml \
  --atomic --timeout 5m
```

> Note: check `infra/helm/<service-name>/values.yaml` to confirm the Helm value name for concurrency. If it is not yet parameterised, set it directly in the values file and redeploy.

**Option B — Scale out the deployment (HPA or manual):**

```bash
# Manual scale (temporary)
kubectl -n railway-platform scale deployment/<service-name> --replicas=<count>

# Check current HPA settings
kubectl -n railway-platform get hpa

# Permanently adjust HPA max replicas via Helm
helm upgrade --install <service-name> infra/helm/<service-name> \
  -n railway-platform \
  --set hpa.maxReplicas=<new-max> \
  -f infra/helm/<service-name>/values-prod.yaml \
  --atomic --timeout 5m
```

Additional replicas add parallel consumer threads across Kafka partitions. The maximum useful replica count equals the number of partitions on the topic being consumed.

### 4.4 Kafka rebalance storm

**Symptoms:** Lag spikes repeatedly rather than growing monotonically; logs show frequent `Rebalance` and `Revoked partitions` messages; consumer group describe shows partitions changing hands repeatedly.

A rebalance storm occurs when consumers repeatedly join and leave the group, preventing any consumer from making sustained progress.

1. Check if consumer pods are restarting (back to Section 4.1).
2. Check for GC pauses longer than `max.poll.interval.ms`:
   ```bash
   kubectl logs -n railway-platform deploy/<service-name> --tail=500 \
     | grep -i "pause\|rebalance\|heartbeat\|poll interval"
   ```
3. If GC pauses are the cause, increase heap or switch to a lower-pause GC configuration (`-XX:+UseZGC`). Create a ticket — this is a code/config change.
4. If `session.timeout.ms` (currently 30,000 ms) is too low for the environment's network latency, increase it:
   - Edit `baseConsumerProps()` in the service's `KafkaConsumerConfig.java`
   - `SESSION_TIMEOUT_MS_CONFIG` must be within the broker's `group.min.session.timeout.ms` and `group.max.session.timeout.ms` range (check MSK cluster config)
   - Redeploy the service

---

## 5. Scaling Procedure

### Manual scale via kubectl (temporary, for immediate lag reduction)

```bash
kubectl -n railway-platform scale deployment/<service-name> --replicas=<count>

# Monitor rollout
kubectl -n railway-platform rollout status deployment/<service-name>

# Confirm all pods are running
kubectl -n railway-platform get pods -l app=<service-name>
```

### Permanent scale via Helm (preferred — persists across deployments)

```bash
helm upgrade --install <service-name> infra/helm/<service-name> \
  -n railway-platform \
  --set replicaCount=<count> \
  -f infra/helm/<service-name>/values-prod.yaml \
  --atomic \
  --timeout 5m
```

After scaling, watch the kafka-consumer-lag dashboard. Lag should begin falling within 2-3 minutes as new consumer pods start and join the group.

---

## 6. Lag Recovery SLO

**Target:** Consumer lag returns to < 100 messages within **15 minutes** of the fix being applied.

If lag has not returned to < 100 within 15 minutes of the fix:
- Re-check all diagnostic steps — the root cause may not have been fully resolved
- Consider whether a DLQ replay is needed (check `railway.dlq` for messages that arrived during the lag period — see `dlq-triage.md`)
- Escalate to on-call if lag continues to grow

A `KafkaConsumerLagCritical` alert that does not resolve within 15 minutes of remediation should be treated as a P1 incident.
