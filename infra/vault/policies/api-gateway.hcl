# Vault policy for api-gateway.
# Grants read-only access to this service's secrets.
# Apply with: vault policy write railway-api-gateway infra/vault/policies/api-gateway.hcl

path "secret/data/railway/api-gateway" {
  capabilities = ["read"]
}

path "secret/metadata/railway/api-gateway" {
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
