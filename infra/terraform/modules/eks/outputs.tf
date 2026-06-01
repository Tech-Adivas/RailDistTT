# modules/eks/outputs.tf

output "cluster_endpoint" {
  description = "API server endpoint URL for the EKS cluster. Use with kubectl and the Kubernetes provider."
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_name" {
  description = "Name of the EKS cluster."
  value       = aws_eks_cluster.this.name
}

output "cluster_oidc_issuer_url" {
  description = "OIDC issuer URL for the cluster. Used to construct IRSA trust policies."
  value       = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

output "oidc_provider_arn" {
  description = "ARN of the IAM OIDC provider. Required when creating IRSA IAM roles for service accounts."
  value       = aws_iam_openid_connect_provider.this.arn
}

output "cluster_certificate_authority_data" {
  description = "Base64-encoded certificate authority data for the cluster. Required by kubectl and the Kubernetes Terraform provider."
  value       = aws_eks_cluster.this.certificate_authority[0].data
}
