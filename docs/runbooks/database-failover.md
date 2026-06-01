# Runbook: RDS PostgreSQL Failover

**CloudWatch alarm:** `RDS_FailoverDetected`
**Severity:** WARNING during automatic failover; CRITICAL if services do not recover
**Last updated:** 2026-06-01

---

## 1. Overview

Each of the platform's six microservices has its own dedicated RDS PostgreSQL 16 instance, provisioned with Multi-AZ deployment. The services and their databases are:

| Service | Database name |
|---|---|
| timetable-service | `timetable_db` |
| schedule-service | `schedule_db` |
| query-service | `query_db` |
| distribution-service | `distribution_db` |
| notification-service | `notification_db` |
| api-gateway | (no own database — stateless) |

In a Multi-AZ deployment, RDS maintains a synchronous standby replica in a second Availability Zone. If the primary instance becomes unhealthy, RDS automatically promotes the standby and updates the CNAME DNS record to point to the new primary.

**Automatic failover timeline:**
- RDS detects the primary failure: ~30 s
- Standby is promoted and brought online: ~30-60 s
- DNS CNAME update propagates: ~15-30 s
- **Total downtime for writes: typically 60-120 seconds**

During the failover window the services will experience write failures. These are handled transparently by the platform through two mechanisms:

1. **`@Retryable` on command handlers:** `TimetableCommandHandler` (and equivalent handlers in other services) retries `TransientDataAccessException` and `CannotAcquireLockException` up to 3 times with 100 ms/200 ms/400 ms back-off. This absorbs most sub-second write failures.
2. **Outbox fallback relay:** If a write succeeded but the Debezium CDC connector was temporarily disconnected during the failover, the `OutboxFallbackRelay` scheduled job (default: runs every 5 minutes) re-publishes any `outbox_events` rows with `relay_published = false` that are older than the lag threshold.

---

## 2. Detecting a Failover

### CloudWatch alarm

RDS publishes a `RDS_FailoverDetected` CloudWatch alarm when a Multi-AZ failover starts. This alarm is the primary detection mechanism. It should trigger a PagerDuty notification to the on-call engineer.

### Grafana signals

Open Grafana → **platform-overview** dashboard and look for:

- HTTP 503 spike on the affected service (write endpoint error rate climbing)
- Latency spike on the service's write endpoints
- `timetable.command.duration` (or equivalent) showing elevated p99

### Spring application logs

```bash
kubectl logs -n railway-platform deploy/<service-name> --tail=200 | grep -i "CannotAcquireLockException\|DataAccessException\|HikariPool\|Connection is closed\|Unable to acquire"
```

Characteristic log patterns during a failover:
- `CannotAcquireLockException: could not obtain connection from the pool within 3000 milliseconds`
- `DataAccessResourceFailureException: could not execute statement` followed by connection errors
- HikariCP log: `HikariPool-1 - Connection is not available, request timed out after 3000ms`
- Spring Retry log: `Retrying... attempt 2 of 3`

---

## 3. Automatic Failover (No Manual Intervention Required)

The following describes what happens automatically — do not intervene during this sequence unless services fail to recover after 5 minutes.

1. **RDS promotes the standby replica** to primary in the secondary AZ. The standby had been receiving synchronous replication writes, so no committed data is lost.

2. **RDS updates the CNAME DNS record** for the instance endpoint (e.g. `timetable-db.cluster-xxxx.eu-west-2.rds.amazonaws.com`) to point to the new primary IP address.

3. **HikariCP reconnects automatically.** The HikariCP connection pool in each service is configured with `connection-test-query: SELECT 1`. When the DNS record updates and the new primary becomes reachable, HikariCP's background health check establishes new connections. No application restart is needed.
   - HikariCP configuration: `maximum-pool-size: 20`, `minimum-idle: 5`, `connection-timeout: 3000`, `idle-timeout: 600000`

4. **`@Retryable` absorbs transient write failures** during the 60-120 s window. Commands that fail with `TransientDataAccessException` or `CannotAcquireLockException` are retried automatically (up to 3 attempts, 100 ms/200 ms/400 ms back-off). Commands that exhaust all retries return HTTP 503 to the caller — clients should retry.

5. **Debezium CDC reconnects** to the new primary automatically. The Debezium PostgreSQL connector uses the same CNAME endpoint and will reconnect and resume WAL streaming once the new primary is online. Check the Debezium connector status after failover (see Post-Failover Checklist, Step 4).

6. **The Outbox fallback relay** runs every 5 minutes and re-publishes any `outbox_events` rows that were not relayed by Debezium during the outage. Because downstream consumers are idempotent (deduplicate on `event_id`), any duplicates from concurrent Debezium + relay publication are harmless.

---

## 4. Manual Failover (Planned Maintenance)

Use this procedure to perform a planned failover, for example to move the primary back to the preferred AZ after an unplanned failover, or before maintenance on the current primary's AZ.

> **Warning:** This will cause 60-120 seconds of write unavailability for the affected service. Schedule during a low-traffic window and notify the operations team.

1. Notify the on-call team and operations that a planned maintenance window is starting for `<service-name>`.

2. Initiate the failover via AWS CLI:
   ```bash
   aws rds reboot-db-instance \
     --db-instance-identifier <rds-instance-id> \
     --force-failover
   ```
   The `--force-failover` flag triggers a Multi-AZ failover rather than a simple reboot. The RDS instance identifier can be found in the Terraform output `rds_instance_id_<service>` or in the AWS RDS console.

3. Monitor the failover progress in the RDS console or via CLI:
   ```bash
   aws rds describe-db-instances \
     --db-instance-identifier <rds-instance-id> \
     --query 'DBInstances[0].DBInstanceStatus'
   ```
   Expected status sequence: `available` → `rebooting` → `available` (on the new primary)

4. Watch the Grafana **platform-overview** dashboard for the affected service to confirm it recovers within 2-3 minutes.

---

## 5. Post-Failover Checklist

Complete all steps after any failover — planned or automatic.

### Step 1 — Verify all services return to healthy

Open Grafana → **platform-overview** dashboard. Check:
- Error rate for all services is < 1%
- Latency has returned to baseline
- Pod restart count has not increased (unexpected restarts post-failover indicate HikariCP failed to reconnect)

```bash
kubectl -n railway-platform get pods
```

All pods should be `Running` with 0 restarts since the failover (a small number of restarts during failover is acceptable if they have since stabilised).

### Step 2 — Check for unrelayed outbox events

Connect to the affected service's database and check for outbox rows that were not published:

```sql
SELECT count(*)
FROM outbox_events
WHERE relay_published = false
  AND created_at < NOW() - INTERVAL '10 minutes';
```

If the count is > 0, these rows were written during the failover but Debezium may not have processed them. The `OutboxFallbackRelay` scheduled job will publish them on its next run (up to 5 minutes). You can wait, or trigger a manual relay by restarting the affected service pod:

```bash
kubectl -n railway-platform rollout restart deployment/<service-name>
```

On restart, the `OutboxFallbackRelay` will run within the configured delay. Watch service logs:

```bash
kubectl logs -n railway-platform deploy/<service-name> --follow --tail=50 \
  | grep -i "outbox\|relay\|fallback"
```

### Step 3 — Check the DLQ

Check whether any messages landed in `railway.dlq` during the failover window (timed with the CloudWatch alarm):

```bash
kubectl -n railway-platform exec -it deploy/timetable-service -- \
  kafka-console-consumer.sh \
  --bootstrap-server $KAFKA_BROKERS \
  --topic railway.dlq \
  --from-beginning \
  --max-messages 20 \
  --property print.headers=true \
  --property print.timestamp=true
```

If DLQ messages are present, triage them using the `dlq-triage.md` runbook. In most cases they will be transient failures from the failover window and can be replayed.

### Step 4 — Verify Debezium CDC connector has reconnected

The Debezium PostgreSQL connector must be actively streaming WAL from the new primary to ensure outbox events continue to be published to Kafka.

Check connector status via the Debezium REST API (accessible within the cluster):

```bash
kubectl -n railway-platform exec -it deploy/timetable-service -- \
  curl -s http://debezium:8083/connectors/<connector-name>/status \
  | python3 -m json.tool
```

The expected response includes `"state": "RUNNING"` for both the connector and all tasks.

If the connector status is `FAILED` or `PAUSED`:

```bash
# Restart the connector
kubectl -n railway-platform exec -it deploy/timetable-service -- \
  curl -X POST http://debezium:8083/connectors/<connector-name>/restart
```

If the connector fails to restart, check the Debezium pod logs:
```bash
kubectl logs -n railway-platform deploy/debezium --tail=200
```

Common causes: the Debezium pod still has a stale connection to the old primary IP (not the CNAME). Restarting the Debezium pod forces it to re-resolve the DNS CNAME:
```bash
kubectl -n railway-platform rollout restart deployment/debezium
```

### Step 5 — Verify consumer lag

Open Grafana → **kafka-consumer-lag** dashboard. Lag on all consumer groups should return to < 100 within 15 minutes of the services recovering. If lag is elevated, refer to the `kafka-consumer-lag.md` runbook.

---

## 6. If Services Do Not Reconnect Automatically

If a service's pods are running but it is not successfully executing database writes after 3-5 minutes post-failover (confirmed by continued error rate in Grafana and connection errors in logs):

```bash
kubectl rollout restart deployment/<service-name> -n railway-platform
```

A rolling restart forces HikariCP to create fresh connections to the current endpoint. This is safe — the rolling restart ensures the service remains partially available during the restart, and `@Retryable` will handle any in-flight requests.

If the restart does not resolve the issue, check:
1. DNS resolution inside the pod — does the CNAME resolve to the new primary's IP?
   ```bash
   kubectl -n railway-platform exec -it deploy/<service-name> -- \
     nslookup <rds-cname-endpoint>
   ```
2. Security group / VPC routing — confirm the pod can reach the new primary's AZ on port 5432
3. Vault Agent sidecar — confirm the `db-password` secret is still being injected correctly (if Vault itself had an issue during the failover window, secrets may not be available):
   ```bash
   kubectl -n railway-platform describe pod <pod-name> | grep -A 5 vault
   ```
