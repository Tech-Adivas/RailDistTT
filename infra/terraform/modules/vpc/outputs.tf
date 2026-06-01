# modules/vpc/outputs.tf

output "vpc_id" {
  description = "ID of the created VPC."
  value       = aws_vpc.this.id
}

output "private_subnet_ids" {
  description = "List of IDs for the private subnets (one per AZ). Pass to EKS, RDS, MSK, and ElastiCache modules."
  value       = aws_subnet.private[*].id
}

output "public_subnet_ids" {
  description = "List of IDs for the public subnets (one per AZ). Used for internet-facing load balancers."
  value       = aws_subnet.public[*].id
}
