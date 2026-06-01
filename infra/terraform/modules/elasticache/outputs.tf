# modules/elasticache/outputs.tf

output "primary_endpoint_address" {
  description = "DNS name of the primary write endpoint for the Redis replication group. Use this as the Redis host in application configuration."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  description = "Port number on which Redis is listening (always 6379)."
  value       = aws_elasticache_replication_group.this.port
}

output "auth_token_secret_arn" {
  description = "ARN of the AWS Secrets Manager secret containing the Redis auth token string. Grant your application's IAM role secretsmanager:GetSecretValue on this ARN."
  value       = aws_secretsmanager_secret.redis_auth.arn
}
