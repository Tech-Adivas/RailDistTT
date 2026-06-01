# Vault policy for notification-service.
# Grants read-only access to this service's secrets.
# Apply with: vault policy write railway-notification-service infra/vault/policies/notification-service.hcl

path "secret/data/railway/notification-service" {
  capabilities = ["read"]
}

path "secret/metadata/railway/notification-service" {
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
