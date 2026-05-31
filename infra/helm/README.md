# infra/helm

## Purpose

Kubernetes Helm charts for deploying all platform services to EKS. One chart per service plus an umbrella chart for deploying everything together.

## Charts

| Chart | Description |
|-------|-------------|
| `timetable-service/` | Timetable Service deployment, HPA, PDB |
| `schedule-service/` | Schedule Service deployment, HPA |
| `query-service/` | Query Service deployment, HPA |
| `distribution-service/` | Distribution Service + WebSocket ingress |
| `notification-service/` | Notification Service deployment |
| `api-gateway/` | API Gateway + ingress + cert-manager |
| `railway-platform/` | Umbrella chart — deploy all services |

## Usage

```bash
# TODO(config): fill values-production.yaml with real endpoints and image tags.
helm upgrade --install railway-platform ./infra/helm/railway-platform \
  -f infra/helm/values-production.yaml \
  --namespace railway \
  --create-namespace
```
