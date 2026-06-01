# Vault policy for query-service.
# Grants read-only access to this service's secrets.
# Apply with: vault policy write railway-query-service infra/vault/policies/query-service.hcl

path "secret/data/railway/query-service" {
  capabilities = ["read"]
}

path "secret/metadata/railway/query-service" {
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
