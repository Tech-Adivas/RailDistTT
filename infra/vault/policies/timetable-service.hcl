# Vault policy for timetable-service.
# Grants read-only access to this service's secrets.
# Apply with: vault policy write railway-timetable-service infra/vault/policies/timetable-service.hcl

path "secret/data/railway/timetable-service" {
  capabilities = ["read"]
}

path "secret/metadata/railway/timetable-service" {
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
