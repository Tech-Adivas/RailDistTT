terraform {
  backend "s3" {
    # TODO(config): Create this S3 bucket manually before running terraform init.
    # Name must be globally unique. Enable versioning and server-side encryption.
    # Example: aws s3api create-bucket --bucket my-company-railway-tf-state-dev \
    #   --region eu-west-2 --create-bucket-configuration LocationConstraint=eu-west-2
    # Then enable versioning: aws s3api put-bucket-versioning \
    #   --bucket my-company-railway-tf-state-dev \
    #   --versioning-configuration Status=Enabled
    bucket = "PLACEHOLDER_TERRAFORM_STATE_BUCKET_NAME"

    key     = "railway-platform/dev/terraform.tfstate"
    region  = "PLACEHOLDER_AWS_REGION"
    encrypt = true

    # TODO(config): Create this DynamoDB table with partition key LockID (String)
    # before running terraform init.
    # Example: aws dynamodb create-table \
    #   --table-name railway-tf-locks-dev \
    #   --attribute-definitions AttributeName=LockID,AttributeType=S \
    #   --key-schema AttributeName=LockID,KeyType=HASH \
    #   --billing-mode PAY_PER_REQUEST
    dynamodb_table = "PLACEHOLDER_TERRAFORM_LOCK_TABLE_NAME"
  }
}
