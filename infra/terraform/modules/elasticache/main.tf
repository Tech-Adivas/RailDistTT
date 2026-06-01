# modules/elasticache/main.tf
# Creates an ElastiCache Redis 7.x replication group with encryption at rest,
# in-transit encryption (TLS), and automatic failover.
# Auth token is auto-generated and stored in AWS Secrets Manager.

resource "random_password" "auth_token" {
  length  = 64
  special = false
  # ElastiCache auth tokens may only contain printable ASCII except @ and "
}

# ── Secrets Manager ───────────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "redis_auth" {
  name                    = "railway/${var.environment}/elasticache/${var.cluster_id}/auth-token"
  description             = "ElastiCache Redis auth token for cluster ${var.cluster_id} in ${var.environment}."
  recovery_window_in_days = 7

  tags = {
    Environment = var.environment
  }
}

resource "aws_secretsmanager_secret_version" "redis_auth" {
  secret_id     = aws_secretsmanager_secret.redis_auth.id
  secret_string = random_password.auth_token.result
}

# ── Security Group ────────────────────────────────────────────────────────────

data "aws_vpc" "selected" {
  id = var.vpc_id
}

resource "aws_security_group" "redis" {
  name        = "railway-${var.environment}-${var.cluster_id}-redis-sg"
  description = "Allow Redis traffic from within the VPC."
  vpc_id      = var.vpc_id

  ingress {
    description = "Redis from within VPC"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "railway-${var.environment}-${var.cluster_id}-redis-sg"
    Environment = var.environment
  }
}

# ── Subnet Group ──────────────────────────────────────────────────────────────

resource "aws_elasticache_subnet_group" "this" {
  name        = "railway-${var.environment}-${var.cluster_id}-subnet-group"
  description = "Subnet group for ElastiCache Redis cluster ${var.cluster_id}."
  subnet_ids  = var.subnet_ids

  tags = {
    Name        = "railway-${var.environment}-${var.cluster_id}-subnet-group"
    Environment = var.environment
  }
}

# ── Replication Group ─────────────────────────────────────────────────────────

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.cluster_id
  description          = "Railway platform Redis cache for ${var.environment}."

  node_type            = var.node_type
  num_cache_clusters   = var.num_cache_clusters
  parameter_group_name = "default.redis7"
  engine_version       = "7.1"
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled  = true
  transit_encryption_enabled  = true
  auth_token                  = random_password.auth_token.result
  automatic_failover_enabled  = true
  multi_az_enabled            = true

  maintenance_window       = "sun:05:00-sun:06:00"
  snapshot_window          = "03:00-04:00"
  snapshot_retention_limit = 7

  tags = {
    Name        = "railway-${var.environment}-${var.cluster_id}"
    Environment = var.environment
  }
}
