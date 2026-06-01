# Vault policy for schedule-service.
# Grants read-only access to this service's secrets.
# Apply with: vault policy write railway-schedule-service infra/vault/policies/schedule-service.hcl

path "secret/data/railway/schedule-service" {
  capabilities = ["read"]
}

path "secret/metadata/railway/schedule-service" {
  capabilities = ["read", "list"]
}

# Allow reading own token info
path "auth/token/lookup-self" {
  capabilities = ["read"]
}

# Allow renewing own token
path "auth/token/renew-self" {
  capabilities = ["update"]
}
