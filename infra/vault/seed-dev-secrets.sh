#!/bin/sh
# Seed HashiCorp Vault with DEV-ONLY dummy secrets for local development.
# These values are NOT real credentials and must NEVER be used in production.
#
# This script runs once after the vault-init container starts.
# Vault is in dev mode with root token: dev-only-root-token

set -e

echo ">> Seeding Vault with DEV-ONLY development secrets..."
echo "   WARNING: These are dummy values for local development only."

VAULT_ADDR=${VAULT_ADDR:-http://vault:8200}
VAULT_TOKEN=${VAULT_TOKEN:-dev-only-root-token}

export VAULT_ADDR VAULT_TOKEN

# Wait for Vault to be ready.
until vault status >/dev/null 2>&1; do
  echo "   Waiting for Vault..."
  sleep 2
done

# Enable KV v2 secrets engine (dev mode already enables it at 'secret/').
vault secrets enable -path=secret kv-v2 2>/dev/null || echo "   kv-v2 already enabled at secret/"

# ── Timetable Service ────────────────────────────────────────────────────────
vault kv put secret/data/railway/timetable-service \
  db-username="railway" \
  db-password="railway_dev_password"   # DEV-ONLY

# ── Schedule Service ─────────────────────────────────────────────────────────
vault kv put secret/data/railway/schedule-service \
  db-username="railway" \
  db-password="railway_dev_password"   # DEV-ONLY

# ── Query Service ────────────────────────────────────────────────────────────
vault kv put secret/data/railway/query-service \
  db-username="railway" \
  db-password="railway_dev_password" \
  redis-password="redis_dev_password"   # DEV-ONLY

# ── Distribution Service ─────────────────────────────────────────────────────
vault kv put secret/data/railway/distribution-service \
  db-username="railway" \
  db-password="railway_dev_password"   # DEV-ONLY

# ── Notification Service ─────────────────────────────────────────────────────
vault kv put secret/data/railway/notification-service \
  db-username="railway" \
  db-password="railway_dev_password" \
  fcm-server-key="DEV-ONLY-FCM-KEY" \
  twilio-account-sid="DEV-ONLY-TWILIO-SID" \
  twilio-auth-token="DEV-ONLY-TWILIO-TOKEN" \
  sendgrid-api-key="DEV-ONLY-SENDGRID-KEY"   # DEV-ONLY

# ── API Gateway ──────────────────────────────────────────────────────────────
vault kv put secret/data/railway/api-gateway \
  oidc-client-secret="DEV-ONLY-OIDC-SECRET" \
  redis-password="redis_dev_password"   # DEV-ONLY

# ── Redis (shared) ───────────────────────────────────────────────────────────
vault kv put secret/data/railway/redis \
  password="redis_dev_password"   # DEV-ONLY

# ── Debezium ─────────────────────────────────────────────────────────────────
vault kv put secret/data/railway/debezium \
  db-username="debezium_user" \
  db-password="debezium_dev_password"   # DEV-ONLY

echo ">> Vault seeding complete."
echo "   Secrets are at: secret/data/railway/<service>"
echo "   Access Vault UI at: http://localhost:8200 (token: dev-only-root-token)"
