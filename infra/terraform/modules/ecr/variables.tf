# modules/ecr/variables.tf

variable "repositories" {
  type        = list(string)
  description = "List of ECR repository names to create, one per container image (e.g. [\"timetable-service\", \"api-gateway\", ...]). Each name must be 2–256 characters, lowercase, and contain only letters, numbers, hyphens, underscores, and forward slashes."
}

variable "environment" {
  type        = string
  description = "Deployment environment name (e.g. 'dev' or 'prod'). Used in resource tags."
}

variable "image_tag_mutability" {
  type        = string
  default     = "IMMUTABLE"
  description = "Tag mutability setting for all repositories. Use 'IMMUTABLE' for prod (prevents tag overwriting, guarantees reproducibility) and 'MUTABLE' for dev (allows reuse of tags like 'latest')."

  validation {
    condition     = contains(["MUTABLE", "IMMUTABLE"], var.image_tag_mutability)
    error_message = "image_tag_mutability must be either 'MUTABLE' or 'IMMUTABLE'."
  }
}
