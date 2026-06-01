# ADR-007: Helm Deployment Strategy for EKS

## Status
Accepted

## Context
Six microservices must be deployed to Amazon EKS across at least two environments (dev and prod).
Each environment has meaningfully different configuration: dev runs single replicas with minimal
resource requests and uses smaller instance types to control cost, while prod runs multiple replicas
with anti-affinity rules, higher CPU and memory limits, and horizontal pod autoscaling enabled. The
deployment toolchain must support these per-environment overrides without duplicating manifest files.

The team evaluated three packaging and delivery strategies. Raw `kubectl apply` with separate
manifest directories per environment was rejected early: it provides no templating, no atomic
rollback, and no release history. Kustomize was evaluated as a middle ground — it handles
environment overlays well but lacks the general-purpose templating power needed to express
conditional resource creation (e.g., HPA only in prod, PodDisruptionBudget only in prod) cleanly.
Helm provides both overlay values and full templating via Go templates, and is the de-facto standard
for Kubernetes packaging with the broadest ecosystem support.

A secondary question was whether to adopt a GitOps model (ArgoCD or Flux) for continuous
reconciliation, or a push-based model driven by GitHub Actions. GitOps offers drift detection and
automatic reconciliation, which are significant operational advantages, but requires provisioning and
maintaining ArgoCD or Flux as additional platform components. Given that Phase 8 is already
introducing Vault, EKS IRSA configuration, and Debezium, adding a GitOps controller was judged to
be out of scope for this phase and is a named Phase 9 candidate.

## Decision
Each service has its own Helm chart located at `infra/helm/<service>/`. Chart structure follows the
standard Helm scaffold: `Chart.yaml`, `values.yaml` (shared defaults), `values-prod.yaml` (prod
overrides), and a `templates/` directory containing Deployment, Service, HorizontalPodAutoscaler,
PodDisruptionBudget, and ServiceAccount manifests. An umbrella chart at `infra/helm/platform/`
declares all six service charts as dependencies, allowing a single `helm upgrade` to deploy the
entire platform when needed (e.g., for a fresh environment bootstrap).

Deployments are executed via GitHub Actions workflow `.github/workflows/cd-deploy.yml` using
`helm upgrade --install --atomic --timeout 5m -f values.yaml -f values-prod.yaml` for production
targets. The `--atomic` flag ensures that if any pod fails its readiness probe within the timeout,
Helm automatically rolls back to the previous release, making failed deployments self-healing without
manual intervention. Service charts are versioned independently, so a single service can be deployed
without touching the rest of the platform.

## Consequences

### Positive
- `--atomic` flag provides automatic rollback on failed deployments — no manual rollback step
  needed in the runbook for standard deployment failures.
- Per-service chart versioning allows independent release cadences; the umbrella chart is used only
  for bootstrapping new environments, not for routine deploys.
- `values-prod.yaml` overlay pattern is explicit and reviewable in Git: the diff between
  `values.yaml` and `values-prod.yaml` documents every prod-specific configuration decision.
- Helm's release history (`helm history <release>`) provides a clear audit trail of every
  deployment, including the chart version and the Git SHA embedded in image tags.
- No new platform components required in Phase 8; GitHub Actions already handles CI and can drive
  `helm upgrade` with minimal additional setup.

### Negative / Trade-offs
- Push-based deployment has no reconciliation loop: if someone manually edits a Kubernetes resource
  outside of Helm (e.g., via `kubectl edit`), the drift will not be detected or corrected until the
  next deployment.
- The umbrella chart creates an all-or-nothing deploy risk: a broken dependency version in one
  service chart can block umbrella-level bootstraps. Individual service deploys are not affected.
- `values-prod.yaml` must be kept in sync manually as new configurable values are added to
  `values.yaml`; there is no automated check that prod overrides are complete.
- Without ArgoCD, there is no self-service UI for viewing live deployment state or triggering
  rollbacks from a dashboard — operators must use `kubectl` and `helm` CLI tools.

## Alternatives Considered

| Alternative | Reason Rejected |
|---|---|
| Raw `kubectl apply` with per-env directories | No templating, no rollback, no release history; high manifest duplication |
| Kustomize | Lacks Go template expressiveness for conditional resource blocks (HPA, PDB); no built-in release versioning |
| ArgoCD (GitOps) | Strong long-term choice but adds a platform component; deferred to Phase 9 |
| Flux (GitOps) | Same rationale as ArgoCD; deferred to Phase 9 |
| Helm + Helmfile | Helmfile adds value for multi-chart orchestration but is an extra tool dependency; umbrella chart achieves the same goal with fewer moving parts |
