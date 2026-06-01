# environments/prod/variables.tf

variable "aws_region" {
  type        = string
  default     = "eu-west-2"
  description = "AWS region to deploy into (e.g. 'eu-west-2'). Find yours in the AWS console top-right dropdown, or run: aws configure get region"
}

variable "environment" {
  type        = string
  default     = "prod"
  description = "Deployment environment label. Do not change this for the prod environment."
}

# ── VPC ──────────────────────────────────────────────────────────────────────

variable "vpc_cidr" {
  type        = string
  default     = "10.1.0.0/16"
  description = "CIDR block for the prod VPC. Must not overlap with the dev VPC or any peered VPCs in your account."
}

variable "availability_zones" {
  type        = list(string)
  description = "List of exactly 3 Availability Zone names in your region (e.g. [\"eu-west-2a\", \"eu-west-2b\", \"eu-west-2c\"]). Obtain with: aws ec2 describe-availability-zones --query 'AvailabilityZones[].ZoneName'"
}

variable "private_subnet_cidrs" {
  type        = list(string)
  default     = ["10.1.0.0/19", "10.1.32.0/19", "10.1.64.0/19"]
  description = "CIDR blocks for the 3 private subnets (one per AZ). Must be sub-ranges of vpc_cidr and must not overlap each other or the public subnets."
}

variable "public_subnet_cidrs" {
  type        = list(string)
  default     = ["10.1.96.0/24", "10.1.97.0/24", "10.1.98.0/24"]
  description = "CIDR blocks for the 3 public subnets (one per AZ). Must be sub-ranges of vpc_cidr and must not overlap each other or the private subnets."
}

# ── EKS ──────────────────────────────────────────────────────────────────────

variable "eks_cluster_name" {
  type        = string
  default     = "railway-prod"
  description = "Name for the EKS cluster. Must be unique within the AWS account and region."
}

# ── RDS ──────────────────────────────────────────────────────────────────────

variable "rds_instance_class" {
  type        = string
  default     = "db.r7g.large"
  description = "RDS instance class applied to all 6 service databases. Use 'db.r7g.large' for prod workloads. See https://aws.amazon.com/rds/instance-types/"
}

variable "rds_allocated_storage" {
  type        = number
  default     = 100
  description = "Initial allocated storage in GB for each RDS instance. Storage auto-scales up to 5x this value."
}

# ── MSK ──────────────────────────────────────────────────────────────────────

variable "msk_cluster_name" {
  type        = string
  default     = "railway-prod-msk"
  description = "Name for the MSK cluster. Must be unique within the AWS account and region."
}

variable "msk_broker_instance_type" {
  type        = string
  default     = "kafka.m5.large"
  description = "MSK broker instance type. Use 'kafka.m5.large' for prod. See https://aws.amazon.com/msk/pricing/"
}

variable "msk_number_of_broker_nodes" {
  type        = number
  default     = 3
  description = "Number of MSK broker nodes. Must equal the number of AZs (3) for even distribution."
}

# ── ElastiCache ───────────────────────────────────────────────────────────────

variable "elasticache_cluster_id" {
  type        = string
  default     = "railway-prod-redis"
  description = "Identifier for the ElastiCache replication group. Must be 1–40 characters, alphanumeric and hyphens only."
}

variable "elasticache_node_type" {
  type        = string
  default     = "cache.r7g.large"
  description = "ElastiCache node type. Use 'cache.r7g.large' for prod. See https://aws.amazon.com/elasticache/pricing/"
}

variable "elasticache_num_cache_clusters" {
  type        = number
  default     = 3
  description = "Number of Redis nodes (1 primary + 2 read replicas) for prod HA. Minimum 2 when automatic failover is enabled."
}
