# modules/rds/outputs.tf

output "db_endpoint" {
  description = "RDS instance endpoint (host:port). Used as the JDBC connection host in application configuration."
  value       = aws_db_instance.this.endpoint
}

output "db_name" {
  description = "Name of the database created in the RDS instance."
  value       = aws_db_instance.this.db_name
}

output "secret_arn" {
  description = "ARN of the AWS Secrets Manager secret containing the database credentials JSON (engine, host, port, dbname, username, password). Grant your application's IAM role secretsmanager:GetSecretValue on this ARN."
  value       = aws_secretsmanager_secret.db_credentials.arn
}
