# Runbook: Deployment

**Workflow file:** `.github/workflows/cd-deploy.yml`
**Namespace:** `railway-platform`
**Last updated:** 2026-06-01

---

## 1. Overview

All platform deployments are push-based via GitHub Actions. The `cd-deploy.yml` workflow deploys each service by running `helm upgrade --install` sequentially. The deployment order is:

1. `timetable-service`
2. `schedule-service`
3. `query-service`
4. `distribution-service`
5. `notification-service`
6. `api-gateway`
7. `operator-console` (frontend)

Every Helm upgrade uses `--atomic --wait --timeout 5m`. The `--atomic` flag means: if a service's deployment fails (pods crash, health checks fail, or the 5-minute timeout is exceeded), Helm automatically rolls back that service's release to its previous revision. Other services that were already deployed in the same run are **not** rolled back — see Section 6 for partial failure handling.

Database migrations (Flyway) run automatically on pod startup before the application begins accepting traffic. If a migration fails, the pod fails its readiness probe and `--atomic` triggers rollback. No migration is ever applied to the database without a pod successfully completing it and passing health checks.

---

## 2. Standard Deployment (Push to `main`)

This is the normal development flow.

1. **Merge your PR** to the `main` branch (squash or merge commit — either is fine).

2. **`ci-backend.yml` triggers automatically** (on paths: `services/**`, `shared/**`, `pom.xml`). It runs the Maven build matrix across all 6 services in parallel:
   - Unit tests (`mvn test`)
   - Integration tests (`mvn verify` — Testcontainers spins up real Kafka, PostgreSQL, Redis)
   - Docker images are built and pushed to ECR, tagged with the `${{ github.sha }}` (the git commit SHA of the merge commit)

3. **`cd-deploy.yml` triggers automatically** when `ci-backend.yml` succeeds on `main`. It deploys to the `dev` environment using the image tag from step 2.

4. **Verify the deployment** in Grafana → **platform-overview** dashboard:
   - Error rate on all services should remain < 1% during and after rollout
   - Latency should be stable at baseline
   - Pod restart count should not be climbing

5. **Watch the rollout in real time** (optional):
   ```bash
   kubectl -n railway-platform get pods -w
   ```

---

## 3. Manual Production Deployment

Production deployments are triggered manually to ensure a human explicitly approves what goes to production. Only image tags that have been successfully deployed to `dev` (and passed all CI checks) should be used.

1. **Find the image tag** (git SHA) of the revision you want to deploy. This is the commit SHA from a passing `ci-backend.yml` run on `main`. It appears in the GitHub Actions run logs as the image tag pushed to ECR.

2. **Go to GitHub Actions** → Select the **"CD — Deploy"** workflow → Click **"Run workflow"**.

3. In the workflow dispatch dialog:
   - **Environment:** select `prod`
   - **Image tag:** paste the git SHA (e.g. `a3f9c12d...`)

4. The `prod` GitHub Environment is configured to require manual approval from a platform engineer before the workflow proceeds. Approve in the GitHub UI when prompted.

5. **Monitor the rollout:**
   ```bash
   kubectl -n railway-platform get pods -w
   ```
   The deployment proceeds sequentially. Each service's rollout must complete (all pods Ready) before the next service begins.

6. **Verify in Grafana** once all services are deployed (as per Section 2, Step 4).

---

## 4. Deployment Health Checks

Helm `--wait` blocks the workflow step until Kubernetes reports the deployment as successfully rolled out. For each service pod, Kubernetes must observe both probes pass before marking the pod `Ready`:

| Probe | Path | Port | Initial delay | Period | Failure threshold |
|---|---|---|---|---|---|
| Liveness | `/actuator/health/liveness` | 8090 | 60 s | 30 s | 3 |
| Readiness | `/actuator/health/readiness` | 8090 | 30 s | 10 s | 3 |

The readiness probe checks:
- Spring application context started
- Database connection pool healthy (HikariCP)
- Flyway migrations completed successfully
- Kafka broker reachable
- Vault secrets loaded (via the `OutboxLagHealthIndicator`)

A pod that fails the readiness probe is removed from the Service's endpoint list (no traffic routed to it). If the pod also repeatedly fails the liveness probe, it is restarted.

If `--wait` does not see all pods become Ready within the `--timeout 5m` window, `--atomic` triggers rollback for that service.

---

## 5. Rollback Procedure

### Automatic rollback (no action required)

If a deployment fails — pods crash, health checks time out, or Flyway migration fails — `--atomic` automatically rolls back the Helm release to the previously deployed revision. The GitHub Actions step will show as failed with the rollback message in its log output.

### Manual rollback

Use this if a deployment succeeded (pods are healthy) but you later discover a regression in production behaviour and need to revert.

1. **Check the release history:**
   ```bash
   helm history <service-name> -n railway-platform
   ```
   This shows all revisions with their deployment timestamps, status, and the image tag used (in the chart's description).

2. **Roll back to a specific revision:**
   ```bash
   helm rollback <service-name> <revision-number> -n railway-platform
   ```
   For example, to roll back `timetable-service` to revision 14:
   ```bash
   helm rollback timetable-service 14 -n railway-platform
   ```

3. **Watch the rollback:**
   ```bash
   kubectl -n railway-platform rollout status deployment/timetable-service
   ```

4. **Verify in Grafana** that error rates and latency return to expected values.

> **Note on database migrations:** Helm rollback reverts the application code and image, but Flyway migrations are **not rolled back**. Flyway migrations must be written to be forward-compatible. If a migration introduced a destructive schema change, a separate remediation migration must be written. Do not rely on Helm rollback to undo database changes.

---

## 6. Partial Deployment Failures

Because services are deployed sequentially, a failure in service N does not roll back services 1 through N-1. The failing service is rolled back to its previous revision by `--atomic`; the already-deployed services remain on the new image tag.

This can result in a mixed-version state on `dev` (uncommon in practice because CI validates all services before deploying). The risk is higher if you have manually deployed individual services.

**To resolve a partial failure:**

1. Investigate the failing service's logs and fix the issue (code bug, bad config, failing migration).
2. Re-deploy only the failing service using a targeted `helm upgrade`:
   ```bash
   helm upgrade --install <service-name> infra/helm/<service-name> \
     -n railway-platform \
     --set image.repository=<ecr-registry>/railway-platform/<service-name> \
     --set image.tag=<git-sha> \
     -f infra/helm/<service-name>/values-prod.yaml \
     --atomic \
     --timeout 5m
   ```
3. Confirm the service is healthy and the deployment is complete before considering the release done.

**Cross-service version compatibility:** All Avro event schemas use BACKWARD compatibility enforced by the Schema Registry. A new consumer can always read events written by an older producer. This means a brief mixed-version window (where some services are on the new image and some are on the old) is safe from a messaging perspective.

---

## 7. Database Migrations

Flyway runs automatically on application startup (`spring.flyway.enabled: true`). Migration scripts live in `services/<service-name>/src/main/resources/db/migration/` and follow the naming convention `V<version>__<description>.sql` (e.g. `V3__add_emergency_justification_column.sql`).

**If a migration fails during deployment:**

The pod will fail its readiness probe, `--wait` will time out, and `--atomic` will roll back the Helm release. The application code is reverted; the database schema is NOT (Flyway does not roll back applied migrations).

To diagnose:

```bash
# Find the pod name (it may be in CrashLoopBackOff or Init:Error)
kubectl -n railway-platform get pods | grep <service-name>

# Read startup logs from the failed pod
kubectl logs -n railway-platform <pod-name> | grep -i flyway

# If the pod has already exited, read from the previous container
kubectl logs -n railway-platform <pod-name> --previous | grep -i flyway
```

Common Flyway failure causes:
- **Checksum mismatch:** A previously applied migration script was modified after being applied. Never modify an existing migration — always add a new one.
- **SQL syntax error:** The migration SQL is invalid for PostgreSQL 16.
- **Constraint violation:** The migration inserts or updates data that violates an existing constraint. Fix the SQL and create a new migration version.
- **Out-of-order migration:** A migration with a version number earlier than the current schema version was added. Enable `outOfOrder=true` only if absolutely required and with team sign-off.

After fixing the migration script, push the fix through a new PR → CI → CD cycle. Do not manually modify the `flyway_schema_history` table unless explicitly directed by the Platform Engineering team.
