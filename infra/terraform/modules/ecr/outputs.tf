# modules/ecr/outputs.tf

output "repository_urls" {
  description = "Map of repository name to full ECR repository URL (e.g. '123456789012.dkr.ecr.eu-west-2.amazonaws.com/timetable-service'). Use these URLs in docker build/push commands and Helm values."
  value       = { for name, repo in aws_ecr_repository.this : name => repo.repository_url }
}
