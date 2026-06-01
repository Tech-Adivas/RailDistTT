# modules/rds/variables.tf

variable "service_name" {
  type        = string
  description = "Name of the microservice this database belongs to (e.g. 'timetable-service'). Used in resource names, tags, and Secrets Manager paths."
}

variable "environment" {
  type        = string
  description = "Deployment environment name (e.g. 'dev' or 'prod'). Used in resource names and tags."
}

variable "vpc_id" {
  type        = string
  description = "ID of the VPC in which to create the RDS instance. Obtain from module.vpc.vpc_id."
}

variable "subnet_ids" {
  type        = list(string)
  description = "List of private subnet IDs for the RDS subnet group. Must span at least 2 AZs. Obtain from module.vpc.private_subnet_ids."
}

variable "instance_class" {
  type        = string
  default     = "db.t3.micro"
  description = "RDS instance class. Use 'db.t3.micro' for dev and 'db.r7g.large' for prod. See https://aws.amazon.com/rds/instance-types/ for available types."
}

variable "allocated_storage" {
  type        = number
  default     = 20
  description = "Initial allocated storage in GB. Storage will auto-scale up to 5x this value. Minimum 20 GB for PostgreSQL."
}

variable "db_name" {
  type        = string
  default     = "railway"
  description = "Name of the initial database to create inside the PostgreSQL instance. Must consist of letters, digits, and underscores only."
}
