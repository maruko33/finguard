resource "aws_ecr_repository" "transaction_api" {
  name                 = "finguard-transaction-api"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  force_delete = true

  tags = {
    Name = "finguard-transaction-api"
  }
}

resource "aws_ecr_repository" "risk_worker" {
  name                 = "finguard-risk-worker"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  force_delete = true

  tags = {
    Name = "finguard-risk-worker"
  }
}