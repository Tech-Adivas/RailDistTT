# modules/rds/main.tf
# Creates one RDS PostgreSQL instance for a single microservice.
# Call this module once per service with a unique service_name.
# Credentials are auto-generated and stored in AWS Secrets Manager.

resource "random_password" "db" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}:?"
}

# ── Secrets Manager ───────────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "db_credentials" {
  name                    = "railway/${var.environment}/${var.service_name}/db-credentials"
  description             = "RDS PostgreSQL credentials for ${var.service_name} in ${var.environment}."
  recovery_window_in_days = 7

  tags = {
    Environment = var.environment
    Service     = var.service_name
  }
}

resource "aws_secretsmanager_secret_version" "db_credentials" {
  secret_id = aws_secretsmanager_secret.db_credentials.id
  secret_string = jsonencode({
    engine   = "postgres"
    host     = aws_db_instance.this.address
    port     = 5432
    dbname   = var.db_name
    username = "railway_${replace(var.service_name, "-", "_")}"
    password = random_password.db.result
  })
}

# ── Security Group ────────────────────────────────────────────────────────────

data "aws_vpc" "selected" {
  id = var.vpc_id
}

resource "aws_security_group" "rds" {
  name        = "railway-${var.environment}-${var.service_name}-rds-sg"
  description = "Allow PostgreSQL traffic from within the VPC for ${var.service_name}."
  vpc_id      = var.vpc_id

  ingress {
    description = "PostgreSQL from within VPC"
    from_port   = 5432
    to_port     = 5432
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
    Name        = "railway-${var.environment}-${var.service_name}-rds-sg"
    Environment = var.environment
    Service     = var.service_name
  }
}

# ── Subnet Group ──────────────────────────────────────────────────────────────

resource "aws_db_subnet_group" "this" {
  name        = "railway-${var.environment}-${var.service_name}-subnet-group"
  description = "Subnet group for RDS instance of ${var.service_name}."
  subnet_ids  = var.subnet_ids

  tags = {
    Name        = "railway-${var.environment}-${var.service_name}-subnet-group"
    Environment = var.environment
    Service     = var.service_name
  }
}

# ── Parameter Group ───────────────────────────────────────────────────────────
# wal_level = logical is required for Debezium CDC.

resource "aws_db_parameter_group" "this" {
  name        = "railway-${var.environment}-${var.service_name}-pg16"
  family      = "postgres16"
  description = "Custom parameter group for ${var.service_name}: enables logical WAL for Debezium CDC."

  parameter {
    name         = "wal_level"
    value        = "logical"
    apply_method = "pending-reboot"
  }

  tags = {
    Name        = "railway-${var.environment}-${var.service_name}-pg16"
    Environment = var.environment
    Service     = var.service_name
  }
}

# ── RDS Instance ──────────────────────────────────────────────────────────────

resource "aws_db_instance" "this" {
  identifier = "railway-${var.environment}-${var.service_name}"

  engine         = "postgres"
  engine_version = "16.3"
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.allocated_storage * 5
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.db_name
  username = "railway_${replace(var.service_name, "-", "_")}"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.this.name
  parameter_group_name   = aws_db_parameter_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  multi_az                = false
  publicly_accessible     = false
  backup_retention_period = 7
  backup_window           = "02:00-03:00"
  maintenance_window      = "Sun:04:00-Sun:05:00"

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "railway-${var.environment}-${var.service_name}-final-snapshot"

  performance_insights_enabled = true

  tags = {
    Name        = "railway-${var.environment}-${var.service_name}"
    Environment = var.environment
    Service     = var.service_name
  }
}
