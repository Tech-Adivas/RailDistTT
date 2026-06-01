# Vault Policies

One policy per service. Apply all policies:

```bash
for service in timetable-service schedule-service query-service distribution-service notification-service api-gateway; do
  vault policy write railway-${service} infra/vault/policies/${service}.hcl
done
```

After applying policies, create Kubernetes auth roles:

```bash
for service in timetable-service schedule-service query-service distribution-service notification-service api-gateway; do
  vault write auth/kubernetes/role/railway-${service} \
    bound_service_account_names=${service} \
    bound_service_account_namespaces=railway-platform \
    policies=railway-${service} \
    ttl=1h
done
```
