# environments/dev/variables.tf

variable "aws_region" {
  type        = string
  default     = "eu-west-2"
  description = "AWS region to deploy into (e.g. 'eu-west-2'). Find yours in the AWS console top-right dropdown, or run: aws configure get region"
}

variable "environment" {
  type        = string
  default     = "dev"
  description = "Deployment environment label. Do not change this for the dev environment."
}

# ── VPC ──────────────────────────────────────────────────────────────────────

variable "vpc_cidr" {
  type        = string
  default     = "10.0.0.0/16"
  description = "CIDR block for the VPC. Must not overlap with other VPCs in your account."
}

variable "availability_zones" {
  type        = list(string)
  description = "List of exactly 3 Availability Zone names in your region (e.g. [\"eu-west-2a\", \"eu-west-2b\", \"eu-west-2c\"]). Obtain with: aws ec2 describe-availability-zones --query 'AvailabilityZones[].ZoneName'"
}

variable "private_subnet_cidrs" {
  type        = list(string)
  default     = ["10.0.0.0/19", "10.0.32.0/19", "10.0.64.0/19"]
  description = "CIDR blocks for the 3 private subnets (one per AZ). Must be sub-ranges of vpc_cidr and must not overlap each other or the public subnets."
}

variable "public_subnet_cidrs" {
  type        = list(string)
  default     = ["10.0.96.0/24", "10.0.97.0/24", "10.0.98.0/24"]
  description = "CIDR blocks for the 3 public subnets (one per AZ). Must be sub-ranges of vpc_cidr and must not overlap each other or the private subnets."
}

# ── EKS ──────────────────────────────────────────────────────────────────────

variable "eks_cluster_name" {
  type        = string
  default     = "railway-dev"
  description = "Name for the EKS cluster. Must be unique within the AWS account and region."
}

# ── RDS ──────────────────────────────────────────────────────────────────────

variable "rds_instance_class" {
  type        = string
  default     = "db.t3.micro"
  description = "RDS instance class applied to all 6 service databases in this environment. Use 'db.t3.micro' for dev. See https://aws.amazon.com/rds/instance-types/"
}

variable "rds_allocated_storage" {
  type        = number
  default     = 20
  description = "Initial allocated storage in GB for each RDS instance. Minimum 20 for PostgreSQL."
}

# ── MSK ──────────────────────────────────────────────────────────────────────

variable "msk_cluster_name" {
  type        = string
  default     = "railway-dev-msk"
  description = "Name for the MSK cluster. Must be unique within the AWS account and region."
}

variable "msk_broker_instance_type" {
  type        = string
  default     = "kafka.t3.small"
  description = "MSK broker instance type. Use 'kafka.t3.small' for dev. See https://aws.amazon.com/msk/pricing/"
}

variable "msk_number_of_broker_nodes" {
  type        = number
  default     = 3
  description = "Number of MSK broker nodes. Must equal the number of AZs (3) for even distribution."
}

# ── ElastiCache ───────────────────────────────────────────────────────────────

variable "elasticache_cluster_id" {
  type        = string
  default     = "railway-dev-redis"
  description = "Identifier for the ElastiCache replication group. Must be 1–40 characters, alphanumeric and hyphens only."
}

variable "elasticache_node_type" {
  type        = string
  default     = "cache.t4g.micro"
  description = "ElastiCache node type. Use 'cache.t4g.micro' for dev. See https://aws.amazon.com/elasticache/pricing/"
}

variable "elasticache_num_cache_clusters" {
  type        = number
  default     = 2
  description = "Number of Redis nodes (1 primary + N-1 read replicas). Minimum 2 when automatic failover is enabled."
}
