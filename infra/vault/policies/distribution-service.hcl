# Vault policy for distribution-service.
# Grants read-only access to this service's secrets.
# Apply with: vault policy write railway-distribution-service infra/vault/policies/distribution-service.hcl

path "secret/data/railway/distribution-service" {
  capabilities = ["read"]
}

path "secret/metadata/railway/distribution-service" {
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
