# infra/terraform

## Purpose

Terraform configurations for all AWS infrastructure: VPC, EKS cluster, RDS PostgreSQL instances (one per service), Amazon MSK (Kafka), ElastiCache Redis, HashiCorp Vault on EKS, IAM roles, and ECR repositories.

All values are placeholders. No real resource IDs, account IDs, or secrets appear here.

## Key Outputs (referenced in docs/CONFIGURATION.md)

| Output | Description |
|--------|-------------|
| `rds_writer_endpoint` | RDS writer endpoint for each service |
| `msk_bootstrap_brokers_sasl_iam` | MSK broker list (SASL/IAM) |
| `schema_registry_url` | Confluent Schema Registry URL |
| `redis_primary_endpoint` | ElastiCache Redis primary endpoint |
| `eks_cluster_name` | EKS cluster name |
| `ecr_registry_url` | ECR registry prefix |
| `vault_address` | HashiCorp Vault address |

## Usage

```bash
# TODO(config): fill terraform.tfvars with your AWS account/region details first.
cd infra/terraform
terraform init
terraform plan -out=plan.tfplan
terraform apply plan.tfplan
```

See individual `.tf` files for `TODO(config)` placeholders.
