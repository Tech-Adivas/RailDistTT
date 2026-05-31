# Vault — Secret Management

## Purpose

HashiCorp Vault is the single source of truth for all secrets in the Railway Timetable Distribution Platform. No secret ever appears in code, config files, images, or environment variables passed at deploy time — only Vault references.

## Secret Path Layout

All secrets use KV v2 at the `secret/` path. The hierarchy is:

```
secret/data/railway/
├── timetable-service/
│   ├── db-username
│   └── db-password
├── schedule-service/
│   ├── db-username
│   └── db-password
├── query-service/
│   ├── db-username
│   ├── db-password
│   └── redis-password
├── distribution-service/
│   ├── db-username
│   └── db-password
├── notification-service/
│   ├── db-username
│   ├── db-password
│   ├── fcm-server-key
│   ├── twilio-account-sid
│   ├── twilio-auth-token
│   └── sendgrid-api-key
├── api-gateway/
│   ├── oidc-client-secret
│   └── redis-password
├── redis/
│   └── password
└── debezium/
    ├── db-username
    └── db-password
```

## How Services Access Secrets

Services use **Spring Cloud Vault** with AppRole authentication:

```yaml
# bootstrap.yml (each service)
spring:
  cloud:
    vault:
      uri: ${VAULT_ADDR}          # injected by Kubernetes
      authentication: APPROLE
      app-role:
        role-id: ${VAULT_ROLE_ID}       # injected by K8s init container
        secret-id: ${VAULT_SECRET_ID}   # injected by K8s init container
  config:
    import: "vault://secret/data/railway/<service-name>"
```

The Vault lease is auto-renewed by Spring Cloud Vault while the service is running.

## Vault Policies

Policies are in `infra/vault/policies/`. Each service has a least-privilege policy granting read access only to its own path:

```hcl
# Example: timetable-service policy
path "secret/data/railway/timetable-service" {
  capabilities = ["read"]
}
path "secret/metadata/railway/timetable-service" {
  capabilities = ["read", "list"]
}
```

## Local Development

In local dev, Vault runs in **dev mode** via docker-compose. Secrets are seeded by `seed-dev-secrets.sh` with clearly marked `DEV-ONLY` dummy values.

```bash
# Access Vault UI
open http://localhost:8200
# Root token (DEV-ONLY): dev-only-root-token

# List secrets via CLI
VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=dev-only-root-token vault kv list secret/data/railway/
```

## Production Setup

1. Deploy Vault on EKS (Helm chart: `hashicorp/vault`).
2. Enable KV v2: `vault secrets enable -path=secret kv-v2`
3. Enable AppRole auth: `vault auth enable approle`
4. Create policies from `infra/vault/policies/`.
5. Create AppRole per service; store role-id in K8s ConfigMap, secret-id in K8s Secret.
6. See `infra/terraform/vault.tf` for automated provisioning.

## Audit Logging

Vault audit logging must be enabled in production:
```bash
vault audit enable file file_path=/vault/logs/audit.log
```
