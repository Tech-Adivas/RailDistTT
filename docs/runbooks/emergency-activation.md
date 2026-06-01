# Runbook: Emergency Timetable Activation

**Endpoint:** `POST /api/v1/timetables/{id}/emergency-activate`
**Required role:** `ROLE_EMERGENCY_OPERATOR`
**Last updated:** 2026-06-01

---

## 1. Overview

Emergency activation is an out-of-band lifecycle transition that immediately moves a timetable from any non-terminal state to `EMERGENCY_ACTIVE`, bypassing the standard `DRAFT → PENDING_REVIEW → APPROVED → ACTIVE` workflow.

Once activated, the distribution service fans out the new timetable to all registered channels within 5 seconds (p95), including the `EMERGENCY_BROADCAST` channel which is reserved exclusively for safety-critical and emergency updates.

**When to use emergency activation:**

- Safety-critical service disruption requiring immediate schedule change (line closure, infrastructure failure)
- Emergency cancellation of services due to an incident on the line
- Immediate safety-critical rerouting ordered by the operations control centre
- Any situation where waiting for the normal approval workflow would create a safety risk or unacceptable passenger impact

**When NOT to use emergency activation:**

- Routine schedule corrections — use the standard approval workflow
- Convenience (e.g. approver is unavailable but there is no urgency)
- Testing — use the test/staging environment

Every emergency activation is a significant operational event. It is logged at `WARN` level by the timetable service, recorded in the audit log, and counted by the `timetable.emergency.activations.total` Prometheus metric.

---

## 2. Authorisation

Emergency activation requires the JWT claim `roles` to include `EMERGENCY_OPERATOR`. This role is issued by the OIDC provider and must be granted by the platform administrator.

The `@PreAuthorize("hasRole('EMERGENCY_OPERATOR')")` guard on the REST controller will return HTTP 403 if the token does not carry this role.

**Post-incident obligations:**

- Emergency activation must be documented in an incident report within **24 hours**
- The Confluence/wiki incident report template must be completed (see Section 6)
- The activation will appear in the audit log for the affected timetable and is retained for compliance purposes
- A post-incident review must be conducted to determine whether the emergency was avoidable through better operational processes

---

## 3. Activation Steps

### Step 1 — Obtain a valid EMERGENCY_OPERATOR JWT

Authenticate via the OIDC provider using your operator credentials. Confirm the token contains the `EMERGENCY_OPERATOR` role:

```bash
# Decode the JWT payload (base64) and check the roles claim
echo "<your-jwt>" | cut -d'.' -f2 | base64 -d | python3 -m json.tool | grep -i role
```

### Step 2a — Activate via the Operator Console (preferred)

1. Navigate to the Operator Console at `https://<operator-console-url>`
2. Log in with your `EMERGENCY_OPERATOR` credentials
3. Search for or navigate to the timetable you need to activate
4. Click the **"Emergency Activate"** button (visible only to users with the `EMERGENCY_OPERATOR` role)
5. In the confirmation dialog, enter a justification. The justification:
   - Is **required** and must not be blank
   - Must describe the operational reason for bypassing the approval workflow
   - Is stored permanently in the audit log and will be reviewed post-incident
   - Maximum 2,000 characters
6. Click **"Confirm Emergency Activation"**
7. The timetable status will transition to `EMERGENCY_ACTIVE` immediately

### Step 2b — Activate via API (fallback if Operator Console is unavailable)

```bash
curl -X POST \
  "https://<api-gateway-url>/api/v1/timetables/{id}/emergency-activate" \
  -H "Authorization: Bearer <your-emergency-operator-jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "justification": "Emergency line closure due to infrastructure failure at Station X. Immediate rerouting required per operations control order #OCO-2026-XXXX."
  }'
```

Expected response: `HTTP 204 No Content` on success.

Error responses:
- `HTTP 403 Forbidden` — token does not carry `EMERGENCY_OPERATOR` role
- `HTTP 404 Not Found` — timetable ID does not exist
- `HTTP 409 Conflict` — timetable is already in a terminal state (`CANCELLED`, `SUPERSEDED`, `REJECTED`) and cannot be activated
- `HTTP 400 Bad Request` — justification is blank or missing

### Step 3 — Verify the status transition

```bash
curl -H "Authorization: Bearer <token>" \
  "https://<api-gateway-url>/api/v1/timetables/{id}" \
  | python3 -m json.tool | grep '"status"'
```

Confirm the response contains `"status": "EMERGENCY_ACTIVE"`.

### Step 4 — Verify distribution fan-out

Open Grafana → **platform-overview** dashboard. Check the distribution rate panel — you should see a spike corresponding to the fan-out to all registered distribution channels.

The distribution service must complete fan-out within **5 seconds** (p95) of the timetable transitioning to `EMERGENCY_ACTIVE`.

### Step 5 — Verify EMERGENCY_BROADCAST channel publication

Confirm that the `EMERGENCY_BROADCAST` channel received the event on the `railway.distribution.events` topic:

```bash
kubectl -n railway-platform exec -it deploy/timetable-service -- \
  kafka-console-consumer.sh \
  --bootstrap-server $KAFKA_BROKERS \
  --topic railway.distribution.events \
  --from-beginning \
  --max-messages 20 \
  --property print.headers=true \
  | grep -i "EMERGENCY_BROADCAST"
```

You should see a distribution event with `channel=EMERGENCY_BROADCAST` and the timetable ID.

---

## 4. Monitoring During the Incident

While an emergency schedule is active, monitor the following:

**Grafana platform-overview dashboard:**
- Error rate on timetable-service and distribution-service (should be < 1%)
- Distribution channel delivery latency
- `timetable.emergency.activations.total` counter

**Kubernetes:**
```bash
# Watch all pods for any restarts
kubectl -n railway-platform get pods -w

# Watch timetable-service logs for errors
kubectl logs -n railway-platform deploy/timetable-service --follow --tail=50
```

**Kafka:**
- Consumer lag on `railway.distribution.events` and `railway.notification.requests` — these should drain promptly
- Watch for any DLQ ingestion (`DLQMessagesPresent` alert) — if distribution events are landing in the DLQ, notifications may not be reaching passengers

**Passenger-facing verification:**
- Confirm updated timetable is visible in the passenger app/query API:
  ```bash
  curl "https://<api-gateway-url>/api/v1/schedules?lineId=<line-id>&date=<date>"
  ```
- Confirm station displays are showing the updated schedule (coordinate with station operations team)

---

## 5. Deactivation

An `EMERGENCY_ACTIVE` timetable does not automatically deactivate. Deactivation happens when a newer timetable for the same line supersedes it.

### Standard deactivation (planned end of emergency)

1. Author a new timetable for the line with the correct post-emergency schedule
2. Submit it through the normal `DRAFT → PENDING_REVIEW → APPROVED` workflow
3. When the new timetable is activated (manually or by the scheduled activation job), the `EMERGENCY_ACTIVE` timetable transitions to `SUPERSEDED`

### Immediate return to normal service

If the emergency has ended and the previous normal-service timetable should be reinstated immediately:

1. Find the previous `ACTIVE` or `APPROVED` timetable for the line (it is now `SUPERSEDED` or `CANCELLED` depending on whether it was displaced)
2. If it was `SUPERSEDED`, you cannot reactivate it directly — create a new timetable as a copy, submit through fast-track approval (still requires a reviewer), and activate
3. If the situation is still safety-critical, a second emergency activation of the correct timetable is permitted — document both activations in the incident report

---

## 6. Post-Incident Requirements

A mandatory post-incident review must be completed **within 24 hours** of emergency deactivation.

**Audit log review:**

```bash
curl -H "Authorization: Bearer <token>" \
  "https://<api-gateway-url>/api/v1/timetables/{id}/audit" \
  | python3 -m json.tool
```

The audit log will contain the emergency activation event, the actor (operator ID), the justification, and all subsequent status transitions.

**Incident report** (Confluence/wiki template):

```
Incident Date/Time:
Timetable ID:
Activating Operator:
Justification (as recorded):
Duration of EMERGENCY_ACTIVE status:
Affected line(s):
Affected services/passengers (estimated):
Root cause of the operational emergency:
Was the emergency avoidable? If so, how?
Follow-up actions (with owners and due dates):
Reviewed by:
Review date:
```

The completed incident report must be linked from the timetable's audit record and stored in the platform's Confluence space under `Operations > Emergency Activations`.

---

## 7. Rollback — If Emergency Activation Was a Mistake

If emergency activation was triggered in error (e.g. wrong timetable ID, test activation in production):

### Cancel the timetable

```bash
curl -X POST \
  "https://<api-gateway-url>/api/v1/timetables/{id}/cancel" \
  -H "Authorization: Bearer <token>"
```

This transitions the timetable to `CANCELLED` (a terminal state).

> **CRITICAL WARNING:** Cancellation does NOT undo distribution that has already been sent to channels. The `EMERGENCY_BROADCAST` notification and all channel fan-out events have already been delivered to passengers, station displays, and partner feeds. You cannot recall them.

### Issue a corrective notification

After cancelling the erroneous timetable, you must immediately issue a corrective notification through all the same channels:

1. Author and submit a new timetable with the correct content and a clear name indicating it is a correction
2. Activate it via emergency activation (yes, a second one) with justification: "Corrective activation following erroneous emergency activation of timetable {id} at {time}."
3. Coordinate directly with the station operations team and customer communications team to issue manual public announcements acknowledging the error

Both activations — the erroneous one and the corrective one — must be documented in the post-incident report.
