# environments/dev/main.tf
# Wires all infrastructure modules together for the dev environment.
# Fill in terraform.tfvars (copied from terraform.tfvars.example) before applying.

terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
  # TODO(config): Configure AWS credentials via environment variables or an IAM role.
  # Never hardcode credentials here.
  # Recommended: export AWS_PROFILE=your-profile
  # Or use IAM instance profiles / EKS IRSA for CI/CD.
}

# ── VPC ───────────────────────────────────────────────────────────────────────

module "vpc" {
  source = "../../modules/vpc"

  environment          = var.environment
  vpc_cidr             = var.vpc_cidr
  availability_zones   = var.availability_zones
  private_subnet_cidrs = var.private_subnet_cidrs
  public_subnet_cidrs  = var.public_subnet_cidrs
  single_nat_gateway   = true
}

# ── EKS ───────────────────────────────────────────────────────────────────────

module "eks" {
  source = "../../modules/eks"

  cluster_name    = var.eks_cluster_name
  cluster_version = "1.31"
  vpc_id          = module.vpc.vpc_id
  subnet_ids      = module.vpc.private_subnet_ids
  environment     = var.environment
}

# ── RDS — one instance per microservice ──────────────────────────────────────

module "rds_timetable_service" {
  source = "../../modules/rds"

  service_name      = "timetable-service"
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  subnet_ids        = module.vpc.private_subnet_ids
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage
  db_name           = "railway"
}

module "rds_schedule_service" {
  source = "../../modules/rds"

  service_name      = "schedule-service"
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  subnet_ids        = module.vpc.private_subnet_ids
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage
  db_name           = "railway"
}

module "rds_query_service" {
  source = "../../modules/rds"

  service_name      = "query-service"
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  subnet_ids        = module.vpc.private_subnet_ids
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage
  db_name           = "railway"
}

module "rds_distribution_service" {
  source = "../../modules/rds"

  service_name      = "distribution-service"
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  subnet_ids        = module.vpc.private_subnet_ids
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage
  db_name           = "railway"
}

module "rds_notification_service" {
  source = "../../modules/rds"

  service_name      = "notification-service"
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  subnet_ids        = module.vpc.private_subnet_ids
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage
  db_name           = "railway"
}

module "rds_api_gateway" {
  source = "../../modules/rds"

  service_name      = "api-gateway"
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  subnet_ids        = module.vpc.private_subnet_ids
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage
  db_name           = "railway"
}

# ── MSK ───────────────────────────────────────────────────────────────────────

module "msk" {
  source = "../../modules/msk"

  cluster_name           = var.msk_cluster_name
  environment            = var.environment
  vpc_id                 = module.vpc.vpc_id
  subnet_ids             = module.vpc.private_subnet_ids
  broker_instance_type   = var.msk_broker_instance_type
  number_of_broker_nodes = var.msk_number_of_broker_nodes
}

# ── ElastiCache Redis ─────────────────────────────────────────────────────────

module "elasticache" {
  source = "../../modules/elasticache"

  cluster_id         = var.elasticache_cluster_id
  environment        = var.environment
  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.private_subnet_ids
  node_type          = var.elasticache_node_type
  num_cache_clusters = var.elasticache_num_cache_clusters
}

# ── ECR ───────────────────────────────────────────────────────────────────────

module "ecr" {
  source = "../../modules/ecr"

  repositories = [
    "timetable-service",
    "schedule-service",
    "query-service",
    "distribution-service",
    "notification-service",
    "api-gateway",
    "operator-console",
  ]
  environment          = var.environment
  image_tag_mutability = "MUTABLE"
}
