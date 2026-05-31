# infra/observability

## Purpose

Observability configuration for the Railway Timetable Distribution Platform:
- **Prometheus** alert rules (SLO burn-rate, DLQ, latency, consumer lag)
- **Grafana** dashboard JSON and datasource provisioning
- **Loki** log aggregation configuration
- **Tempo** distributed tracing configuration
- **Grafana Alloy** OTel collector configuration

## Contents

| File | Purpose |
|------|---------|
| `prometheus.yml` | Prometheus scrape config |
| `alert-rules/slo-alerts.yml` | SLO burn-rate + operational alerts |
| `loki.yml` | Loki server config |
| `tempo.yml` | Tempo server config |
| `alloy-config.alloy` | Grafana Alloy (OTel collector + log scraper) |
| `grafana/provisioning/` | Grafana datasource + dashboard auto-provisioning |
| `grafana/dashboards/` | Grafana dashboard JSON files |

## Key Alerts

| Alert | Severity | Description |
|-------|----------|-------------|
| `ReadApiAvailabilityFastBurn` | critical | Error budget burning too fast (1h window) |
| `ReadApiLatencyP99High` | warning | p99 > 300 ms |
| `WriteApiLatencyP99High` | warning | p99 > 800 ms |
| `KafkaConsumerLagCritical` | critical | Consumer lag > 10k messages |
| `DLQMessagesPresent` | warning | Any message in DLQ |
| `ServiceErrorRateSpike` | warning | Service error rate > 5% |

## Local Access

After `make up`:
- Grafana: http://localhost:3000 (admin/admin)
- Prometheus: http://localhost:9090
- Loki: http://localhost:3100
- Tempo: http://localhost:3200
