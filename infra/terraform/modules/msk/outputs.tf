# modules/msk/outputs.tf

output "bootstrap_brokers_sasl_scram" {
  description = "Comma-separated list of SASL/SCRAM broker endpoints (port 9096, TLS). Use this as the bootstrap.servers value in Kafka client configuration."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
}

output "zookeeper_connect_string" {
  description = "Comma-separated list of ZooKeeper connection endpoints. Required for some administrative tools; prefer broker-side operations for Kafka 3.x."
  value       = aws_msk_cluster.this.zookeeper_connect_string
}

output "cluster_arn" {
  description = "ARN of the MSK cluster. Used for IAM policies and CloudWatch metric dimensions."
  value       = aws_msk_cluster.this.arn
}
