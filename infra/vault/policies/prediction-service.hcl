# Vault policy for the Python AI Prediction Service.
# Grants read access to all prediction-service secrets.
# Applied to the Kubernetes auth role: prediction-service

path "secret/data/railway/prediction-service/*" {
  capabilities = ["read"]
}

path "secret/metadata/railway/prediction-service/*" {
  capabilities = ["list", "read"]
}

path "auth/token/renew-self" {
  capabilities = ["update"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}
