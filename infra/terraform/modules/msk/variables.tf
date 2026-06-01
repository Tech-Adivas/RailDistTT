# modules/msk/variables.tf

variable "cluster_name" {
  type        = string
  description = "Name for the MSK cluster (e.g. 'railway-dev-msk'). Must be unique within the AWS account and region."
}

variable "environment" {
  type        = string
  description = "Deployment environment name (e.g. 'dev' or 'prod'). Used in resource names and tags."
}

variable "vpc_id" {
  type        = string
  description = "ID of the VPC in which to create the MSK cluster. Obtain from module.vpc.vpc_id."
}

variable "subnet_ids" {
  type        = list(string)
  description = "List of private subnet IDs for MSK broker placement. Must contain exactly one subnet per AZ and match number_of_broker_nodes. Obtain from module.vpc.private_subnet_ids."
}

variable "broker_instance_type" {
  type        = string
  default     = "kafka.t3.small"
  description = "EC2 instance type for MSK brokers. Use 'kafka.t3.small' for dev and 'kafka.m5.large' for prod. See https://aws.amazon.com/msk/pricing/ for available types."
}

variable "number_of_broker_nodes" {
  type        = number
  default     = 3
  description = "Total number of MSK broker nodes. Must be a multiple of the number of AZs. Minimum 3 for production use. Each subnet in subnet_ids receives number_of_broker_nodes / len(subnet_ids) brokers."
}
