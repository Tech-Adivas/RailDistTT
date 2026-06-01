# modules/vpc/variables.tf

variable "vpc_cidr" {
  type        = string
  default     = "10.0.0.0/16"
  description = "CIDR block for the VPC (e.g. '10.0.0.0/16'). Must not overlap with existing VPCs in the target account."
}

variable "environment" {
  type        = string
  description = "Deployment environment name (e.g. 'dev' or 'prod'). Used in resource names and tags."
}

variable "availability_zones" {
  type        = list(string)
  description = "List of AWS Availability Zone names to deploy into. Must be exactly 3 entries and must exist in your region. Obtain with: aws ec2 describe-availability-zones --query 'AvailabilityZones[].ZoneName'"
}

variable "private_subnet_cidrs" {
  type        = list(string)
  description = "List of CIDR blocks for the 3 private subnets (one per AZ). Must be sub-ranges of vpc_cidr and must not overlap each other or the public subnets. Example: [\"10.0.0.0/19\", \"10.0.32.0/19\", \"10.0.64.0/19\"]"
}

variable "public_subnet_cidrs" {
  type        = list(string)
  description = "List of CIDR blocks for the 3 public subnets (one per AZ). Must be sub-ranges of vpc_cidr and must not overlap each other or the private subnets. Example: [\"10.0.96.0/24\", \"10.0.97.0/24\", \"10.0.98.0/24\"]"
}

variable "single_nat_gateway" {
  type        = bool
  default     = false
  description = "When true, a single NAT gateway is created in the first AZ and all private subnets route through it. Set to true for dev environments to reduce cost. Set to false for prod to ensure HA (one NAT gateway per AZ)."
}
