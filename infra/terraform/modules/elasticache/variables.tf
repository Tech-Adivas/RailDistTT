# modules/elasticache/variables.tf

variable "cluster_id" {
  type        = string
  description = "Identifier for the ElastiCache replication group (e.g. 'railway-dev-redis'). Must be 1–40 alphanumeric characters or hyphens, and unique within the AWS account and region."
}

variable "environment" {
  type        = string
  description = "Deployment environment name (e.g. 'dev' or 'prod'). Used in resource names and tags."
}

variable "vpc_id" {
  type        = string
  description = "ID of the VPC in which to create the ElastiCache cluster. Obtain from module.vpc.vpc_id."
}

variable "subnet_ids" {
  type        = list(string)
  description = "List of private subnet IDs for the ElastiCache subnet group. Use subnets in at least 2 different AZs for multi-AZ failover. Obtain from module.vpc.private_subnet_ids."
}

variable "node_type" {
  type        = string
  default     = "cache.t4g.micro"
  description = "ElastiCache node type. Use 'cache.t4g.micro' for dev and 'cache.r7g.large' for prod. See https://aws.amazon.com/elasticache/pricing/ for available types."
}

variable "num_cache_clusters" {
  type        = number
  default     = 2
  description = "Number of cache clusters (nodes) in the replication group. Must be at least 2 when automatic_failover_enabled is true (1 primary + 1+ read replicas). Use 2 for dev and 3 for prod."
}
