# ADR-006: HashiCorp Vault Secret Management Strategy

## Status
Accepted

## Context
The platform comprises six microservices (timetable-service, schedule-service, distribution-service,
notification-service, audit-service, and api-gateway). Each service requires database credentials
(PostgreSQL username/password per schema), Kafka SASL credentials (username/password for broker
authentication), and one or more service-specific secrets: the api-gateway holds an OIDC client
secret; notification-service holds FCM server keys, Twilio account SID/auth-token, and a SendGrid
API key. In total this amounts to roughly 20–30 distinct secret values across the platform.

The non-negotiable constraint is that no secret may ever appear in source code, application config
files, Git history, or Docker image layers. This rules out any approach that embeds secrets at build
time or stores them in plaintext Kubernetes manifests. The secret management solution must also be
auditable — the security team requires an access log showing which pod read which secret and when.

Four candidate approaches were evaluated: plain environment variables injected by EKS (through a
`secrets.env` ConfigMap or pod spec), native Kubernetes Secrets (base64-encoded, stored in etcd),
AWS Secrets Manager accessed via the AWS SDK at runtime, and HashiCorp Vault with two possible
integration paths (Spring Cloud Vault and the Vault Agent Sidecar Injector).

AWS Secrets Manager satisfies the core security constraint but introduces tight AWS-vendor coupling
into the application code: every secret read requires an AWS SDK call, which ties the application
to the AWS IAM model and makes local development and CI testing harder without mocking. Kubernetes
Secrets are base64, not encrypted, and are stored in plaintext in etcd unless a separate KMS
encryption provider is configured — an additional operational step that is easy to overlook.
Environment variables injected via EKS are visible in `kubectl describe pod` output, which violates
the audit trail requirement and exposes secrets to anyone with read access to pod descriptions.

## Decision
HashiCorp Vault is adopted as the single secrets backend for all environments. In production (EKS),
the Vault Agent Sidecar Injector is used: Vault Agent runs as a sidecar container, authenticates to
Vault via Kubernetes IRSA (IAM Roles for Service Accounts), fetches secrets, and writes them to an
in-memory `tmpfs` emptyDir volume that the application container mounts. The application container
never calls Vault directly and never holds a Vault token.

In all environments (local dev, CI, and production) Spring Cloud Vault is configured with
`spring.config.import: vault://` so that secret keys are resolved as standard Spring
`@Value`-injectable properties. In local development, Vault runs in dev mode (single-process, no
storage backend) and a shell script `infra/vault/seed-dev-secrets.sh` seeds dummy values for every
required path on startup. This means the local developer workflow is identical in shape to
production: start Vault dev, run the seed script, start the service.

## Consequences

### Positive
- Secrets never touch the application container's filesystem; Vault Agent writes exclusively to a
  `tmpfs` emptyDir, which is never persisted to disk or visible in container image layers.
- Full audit log: every secret read is recorded in Vault's audit backend, giving the security team
  a timestamped trail of which service identity accessed which secret path.
- Spring Cloud Vault abstracts the secret source behind the standard Spring Environment, so
  application code has zero awareness of Vault — it only sees injected property values.
- Dynamic credential rotation is architecturally possible for PostgreSQL in a future phase via
  Vault's database secrets engine, without changing application code.
- Local development workflow is self-contained: `infra/vault/seed-dev-secrets.sh` is version-
  controlled and documents every required secret path, acting as living documentation.

### Negative / Trade-offs
- Vault is a new operational dependency; a Vault HA cluster (3-node Raft) must be provisioned and
  maintained in production, adding infrastructure complexity.
- EKS pods authenticate via IRSA: each service's Kubernetes ServiceAccount must be annotated with
  the correct IAM role ARN, and Vault's Kubernetes auth method must be configured per role. Initial
  setup is non-trivial.
- Vault Agent Sidecar adds a sidecar container to every pod, increasing per-pod resource overhead
  (approximately 50 MiB RAM per pod).
- If Vault becomes unavailable at pod startup, the sidecar init container will block the
  application container from starting — Vault HA and health checks are therefore on the critical
  path for deployment availability.

## Alternatives Considered

| Alternative | Reason Rejected |
|---|---|
| AWS Secrets Manager (SDK calls) | Vendor-locks secret access path to AWS SDK; complicates local dev and CI without mocking; no Spring-native integration as clean as Spring Cloud Vault |
| Kubernetes Secrets (base64) | Plaintext in etcd without KMS encryption provider; visible in `kubectl get secret -o yaml`; no audit log |
| Environment variables via EKS | Secrets visible in `kubectl describe pod`; no audit trail; easy to accidentally log via env dump |
| Spring Cloud Vault without Agent Sidecar | App container holds a Vault token directly; token renewal logic must be handled by the app; less secure than agent-mediated access |
