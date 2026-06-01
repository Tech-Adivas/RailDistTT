# Configuration Reference

Every environment-specific value in this platform is a placeholder. This document lists every placeholder, how to obtain its real value, and which Vault path or Terraform output supplies it.

## Legend

| Column | Meaning |
|--------|---------|
| Placeholder | The literal string used in config files |
| Source | Where the real value comes from |
| Vault path | Secret path in HashiCorp Vault (if a secret) |
| Terraform output | `terraform output <name>` in `infra/terraform/` |

---

## Database (PostgreSQL / RDS)

| Placeholder | Description | Source | Vault path |
|-------------|-------------|--------|-----------|
| `PLACEHOLDER_RDS_ENDPOINT` | RDS writer endpoint hostname | Terraform output `rds_writer_endpoint` | — |
| `PLACEHOLDER_RDS_READ_ENDPOINT` | RDS reader endpoint (read replica) | Terraform output `rds_reader_endpoint` | — |
| `vault.secret.db-username` | DB username | — | `secret/data/railway/<service>/db-username` |
| `vault.secret.db-password` | DB password | — | `secret/data/railway/<service>/db-password` |

## Kafka (MSK)

| Placeholder | Description | Source | Vault path |
|-------------|-------------|--------|-----------|
| `PLACEHOLDER_MSK_BROKER_1` | MSK bootstrap broker 1 (SASL/IAM) | Terraform output `msk_bootstrap_brokers_sasl_iam` | — |
| `PLACEHOLDER_MSK_BROKER_2` | MSK bootstrap broker 2 | Same output (comma-separated) | — |
| `PLACEHOLDER_SCHEMA_REGISTRY_URL` | Confluent Schema Registry URL | Terraform output `schema_registry_url` | — |
| `vault.secret.kafka-username` | Kafka SASL username (if not IAM) | — | `secret/data/railway/<service>/kafka-username` |
| `vault.secret.kafka-password` | Kafka SASL password | — | `secret/data/railway/<service>/kafka-password` |

## Redis (ElastiCache)

| Placeholder | Description | Source | Vault path |
|-------------|-------------|--------|-----------|
| `PLACEHOLDER_REDIS_HOST` | ElastiCache Redis primary endpoint | Terraform output `redis_primary_endpoint` | — |
| `PLACEHOLDER_REDIS_PORT` | Redis port (default 6379) | Terraform output or hardcode 6379 | — |
| `vault.secret.redis-password` | Redis AUTH password | — | `secret/data/railway/redis/password` |

## HashiCorp Vault

| Placeholder | Description | Source |
|-------------|-------------|--------|
| `PLACEHOLDER_VAULT_ADDR` | Vault server address | Terraform output `vault_address` |
| `PLACEHOLDER_VAULT_TOKEN` | Vault token (dev only) | Set to `dev-only-root-token` in docker-compose |
| `vault.role-id` | AppRole role ID (prod) | Vault operator |
| `vault.secret-id` | AppRole secret ID (prod) | Vault operator (rotate regularly) |

## OIDC / Authentication

| Placeholder | Description | Source | Vault path |
|-------------|-------------|--------|-----------|
| `PLACEHOLDER_OIDC_ISSUER_URI` | OIDC issuer URI (e.g. Cognito user pool) | AWS Cognito console or Terraform | — |
| `PLACEHOLDER_OIDC_JWK_URI` | JWKS endpoint for JWT validation | Derived from issuer: `{issuer}/.well-known/jwks.json` | — |
| `vault.secret.oidc-client-secret` | OIDC client secret (for gateway) | — | `secret/data/railway/api-gateway/oidc-client-secret` |

## Notification Providers

| Placeholder | Description | Source | Vault path |
|-------------|-------------|--------|-----------|
| `vault.secret.fcm-server-key` | Firebase Cloud Messaging server key | Firebase console | `secret/data/railway/notification-service/fcm-server-key` |
| `vault.secret.twilio-account-sid` | Twilio account SID (SMS) | Twilio console | `secret/data/railway/notification-service/twilio-account-sid` |
| `vault.secret.twilio-auth-token` | Twilio auth token | Twilio console | `secret/data/railway/notification-service/twilio-auth-token` |
| `vault.secret.sendgrid-api-key` | SendGrid API key (email) | SendGrid console | `secret/data/railway/notification-service/sendgrid-api-key` |
| `PLACEHOLDER_NOTIFICATION_FROM_EMAIL` | From address for email notifications | Operational config | — |
| `PLACEHOLDER_NOTIFICATION_FROM_SMS` | From phone number for SMS | Twilio console | — |

## AWS Infrastructure

| Placeholder | Description | Source |
|-------------|-------------|--------|
| `PLACEHOLDER_AWS_REGION` | AWS region (e.g. `eu-west-1`) | Infrastructure decision |
| `PLACEHOLDER_AWS_ACCOUNT_ID` | 12-digit AWS account ID | AWS console |
| `PLACEHOLDER_ECR_REGISTRY` | ECR registry URL (`<account>.dkr.ecr.<region>.amazonaws.com`) | Terraform output `ecr_registry_url` |
| `PLACEHOLDER_EKS_CLUSTER_NAME` | EKS cluster name | Terraform output `eks_cluster_name` |

## Observability

| Placeholder | Description | Source |
|-------------|-------------|--------|
| `PLACEHOLDER_OTEL_ENDPOINT` | OpenTelemetry collector endpoint | Terraform / cluster config |
| `PLACEHOLDER_GRAFANA_URL` | Grafana instance URL | Terraform output |
| `PLACEHOLDER_LOKI_URL` | Loki push endpoint | Terraform output |

## Per-Service Kafka Topics

| Placeholder | Topic | Notes |
|-------------|-------|-------|
| `PLACEHOLDER_TOPIC_TIMETABLE_CHANGED` | `railway.timetable.changed` | Suggest keeping this exact name |
| `PLACEHOLDER_TOPIC_SCHEDULE_COMPUTED` | `railway.schedule.computed` | |
| `PLACEHOLDER_TOPIC_DISTRIBUTION_EVENTS` | `railway.distribution.events` | |
| `PLACEHOLDER_TOPIC_NOTIFICATION_REQUESTS` | `railway.notification.requests` | |
| `PLACEHOLDER_TOPIC_DLQ` | `railway.dlq` | Dead letter queue |

---

## How Secrets Are Injected

In production, Spring Cloud Vault (`spring.cloud.vault.*`) bootstraps before the application context. Vault secrets are mapped to Spring properties via `spring.config.import: vault://`. Example:

```yaml
spring:
  config:
    import: "vault://secret/data/railway/timetable-service"
  cloud:
    vault:
      uri: ${VAULT_ADDR:http://PLACEHOLDER_VAULT_ADDR:8200}
      authentication: APPROLE
      app-role:
        role-id: ${VAULT_ROLE_ID}       # injected by Kubernetes init container
        secret-id: ${VAULT_SECRET_ID}   # injected by Kubernetes init container
```

In local dev, Vault runs in dev mode with `VAULT_DEV_ROOT_TOKEN_ID=dev-only-root-token`. A seed script (`infra/vault/seed-dev-secrets.sh`) populates dummy values clearly marked `DEV-ONLY`.

---

## Helm Chart Values

| Placeholder | File | Description | Source |
|---|---|---|---|
| `PLACEHOLDER_ECR_REGISTRY` | `infra/helm/*/values.yaml` | ECR registry URL (`<account>.dkr.ecr.<region>.amazonaws.com`) | Terraform output `ecr_registry_url` |
| `PLACEHOLDER_IAM_ROLE_ARN_TIMETABLE_SERVICE` | `infra/helm/timetable-service/values.yaml` | IRSA IAM role ARN for timetable-service | Terraform output `iam_role_arn_timetable_service` |
| `PLACEHOLDER_IAM_ROLE_ARN_SCHEDULE_SERVICE` | `infra/helm/schedule-service/values.yaml` | IRSA IAM role ARN for schedule-service | Terraform output `iam_role_arn_schedule_service` |
| `PLACEHOLDER_IAM_ROLE_ARN_QUERY_SERVICE` | `infra/helm/query-service/values.yaml` | IRSA IAM role ARN for query-service | Terraform output `iam_role_arn_query_service` |
| `PLACEHOLDER_IAM_ROLE_ARN_DISTRIBUTION_SERVICE` | `infra/helm/distribution-service/values.yaml` | IRSA IAM role ARN for distribution-service | Terraform output `iam_role_arn_distribution_service` |
| `PLACEHOLDER_IAM_ROLE_ARN_NOTIFICATION_SERVICE` | `infra/helm/notification-service/values.yaml` | IRSA IAM role ARN for notification-service | Terraform output `iam_role_arn_notification_service` |
| `PLACEHOLDER_IAM_ROLE_ARN_API_GATEWAY` | `infra/helm/api-gateway/values.yaml` | IRSA IAM role ARN for api-gateway | Terraform output `iam_role_arn_api_gateway` |
| `PLACEHOLDER_ACM_CERT_ARN` | `infra/helm/api-gateway/templates/ingress.yaml` | ACM certificate ARN for HTTPS on ALB | AWS Certificate Manager console or Terraform |
| `PLACEHOLDER_GATEWAY_HOSTNAME` | `infra/helm/api-gateway/templates/ingress.yaml` | Public DNS hostname (e.g. `api.railway.example.com`) | DNS/Route53 configuration |

## GitHub Actions Secrets

| Secret | Workflow | Description | How to obtain |
|---|---|---|---|
| `AWS_ACCESS_KEY_ID` | All | IAM access key for CI/CD | AWS IAM console — create dedicated CI user with ECR push + EKS access |
| `AWS_SECRET_ACCESS_KEY` | All | IAM secret key | Same as above |
| `AWS_REGION` | All | AWS region (e.g. `eu-west-2`) | Your deployment region |
| `EKS_CLUSTER_NAME` | `cd-deploy.yml` | EKS cluster name | Terraform output `eks_cluster_name` |
| `SLACK_WEBHOOK_URL` | `cd-deploy.yml` | Slack incoming webhook for deploy notifications | Slack app settings → Incoming Webhooks |
| `NVD_API_KEY` | `security-scan.yml` | NVD API key for OWASP dependency check (avoids rate limiting) | https://nvd.nist.gov/developers/request-an-api-key |

## Terraform Backend (Bootstrap)

These must be created manually before running `terraform init`. They are NOT managed by Terraform itself.

| Placeholder | File | Description | How to create |
|---|---|---|---|
| `PLACEHOLDER_TERRAFORM_STATE_BUCKET_NAME` | `infra/terraform/environments/*/backend.tf` | S3 bucket for Terraform state | `aws s3api create-bucket --bucket <name> --region <region>` then enable versioning and SSE |
| `PLACEHOLDER_TERRAFORM_LOCK_TABLE_NAME` | `infra/terraform/environments/*/backend.tf` | DynamoDB table for state locking | `aws dynamodb create-table --table-name <name> --attribute-definitions AttributeName=LockID,AttributeType=S --key-schema AttributeName=LockID,KeyType=HASH --billing-mode PAY_PER_REQUEST` |
| `PLACEHOLDER_AWS_REGION` | `infra/terraform/environments/*/backend.tf` | Region for state bucket | Match your deployment region |

## Angular Frontend (Production)

| Placeholder | File | Description | Source |
|---|---|---|---|
| `PLACEHOLDER_API_GATEWAY_URL` | `frontend/operator-console/src/environments/environment.prod.ts` | Full URL of the API Gateway (e.g. `https://api.railway.example.com`) | ALB DNS or Route53 — matches `PLACEHOLDER_GATEWAY_HOSTNAME` |
| `PLACEHOLDER_WS_URL` | `frontend/operator-console/src/environments/environment.prod.ts` | WebSocket URL (e.g. `wss://api.railway.example.com/ws`) | Same host as API gateway, `/ws` path |
| `PLACEHOLDER_OIDC_ISSUER_URL` | `frontend/operator-console/src/environments/environment.prod.ts` | OIDC issuer URL (e.g. Cognito user pool URL) | AWS Cognito console or Terraform output `cognito_user_pool_endpoint` |
| `PLACEHOLDER_OIDC_CLIENT_ID` | `frontend/operator-console/src/environments/environment.prod.ts` | OIDC public client ID | AWS Cognito app client settings |
