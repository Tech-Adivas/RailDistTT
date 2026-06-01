# modules/eks/variables.tf

variable "cluster_name" {
  type        = string
  description = "Name for the EKS cluster (e.g. 'railway-dev'). Must be unique within the AWS account and region."
}

variable "cluster_version" {
  type        = string
  default     = "1.31"
  description = "Kubernetes version for the EKS control plane (e.g. '1.31'). Check AWS docs for supported versions in your region."
}

variable "vpc_id" {
  type        = string
  description = "ID of the VPC in which to create the EKS cluster. Obtain from module.vpc.vpc_id."
}

variable "subnet_ids" {
  type        = list(string)
  description = "List of subnet IDs for the EKS control plane and managed node groups. Use private subnet IDs from module.vpc.private_subnet_ids."
}

variable "environment" {
  type        = string
  description = "Deployment environment name (e.g. 'dev' or 'prod'). Used in resource names and tags."
}
