# modules/msk/main.tf
# Creates an Amazon MSK (Managed Streaming for Kafka) cluster with
# SASL/SCRAM + TLS authentication, custom broker config, and CloudWatch logging.

# ── CloudWatch Log Group ──────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/railway/${var.environment}/msk/${var.cluster_name}"
  retention_in_days = 30

  tags = {
    Environment = var.environment
  }
}

# ── Security Group ────────────────────────────────────────────────────────────

data "aws_vpc" "selected" {
  id = var.vpc_id
}

resource "aws_security_group" "msk" {
  name        = "railway-${var.environment}-msk-sg"
  description = "Allow SASL/SCRAM TLS Kafka traffic from within the VPC."
  vpc_id      = var.vpc_id

  ingress {
    description = "Kafka SASL/SCRAM TLS from within VPC"
    from_port   = 9096
    to_port     = 9096
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  ingress {
    description = "Kafka plaintext (internal broker communication)"
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  ingress {
    description = "ZooKeeper from within VPC"
    from_port   = 2181
    to_port     = 2181
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
    Name        = "railway-${var.environment}-msk-sg"
    Environment = var.environment
  }
}

# ── MSK Configuration ─────────────────────────────────────────────────────────

resource "aws_msk_configuration" "this" {
  name              = "railway-${var.environment}-msk-config"
  description       = "Custom MSK broker configuration for the Railway platform."
  kafka_versions    = ["3.7.x"]

  server_properties = <<-PROPS
    auto.create.topics.enable=false
    log.retention.hours=168
    default.replication.factor=3
    min.insync.replicas=2
    offsets.topic.replication.factor=3
    log.message.format.version=3.7
  PROPS
}

# ── MSK Cluster ───────────────────────────────────────────────────────────────

resource "aws_msk_cluster" "this" {
  cluster_name           = var.cluster_name
  kafka_version          = "3.7.x"
  number_of_broker_nodes = var.number_of_broker_nodes

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = 100
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  client_authentication {
    sasl {
      scram = true
    }
    tls {}
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  broker_logs {
    cloudwatch_logs {
      enabled   = true
      log_group = aws_cloudwatch_log_group.msk.name
    }
  }

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  tags = {
    Name        = var.cluster_name
    Environment = var.environment
  }
}
