#Declared resource
resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"

  client_id_list = [
    "sts.amazonaws.com"
  ]
}
#and role
resource "aws_iam_role" "github_terraform_plan" {
  name = "finguard-github-terraform-plan"

  assume_role_policy = data.aws_iam_policy_document.github_terraform_plan_assume.json
}

#先拿aws account info
data "aws_caller_identity" "current" {}

#this avoid aws account identifier hard coded in here
locals {
  state_bucket_name = "finguard-tfstate-${data.aws_caller_identity.current.account_id}-${var.aws_region}"

  state_key = "finguard/dev/terraform.tfstate"

  github_subject_prefix = "repo:${var.github_owner}@${var.github_owner_id}/${var.github_repo}@${var.github_repo_id}"
}

#add S3 State Bucket
resource "aws_s3_bucket" "terraform_state" {
  bucket = local.state_bucket_name

  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Name = "finguard-terraform-state"
  }
}

#Versioning S3 bucket
resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  versioning_configuration {
    status = "Enabled"
  }
}

#Encryption S3 bucket
resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# avoid public access
resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}


#Github Terraform Plan Role - AWS only allows maruko33/finguard的pull request and main
data "aws_iam_policy_document" "github_terraform_plan_assume" {

  statement {
    effect = "Allow"

    actions = [
      "sts:AssumeRoleWithWebIdentity"
    ]

    principals {
      type = "Federated"

      identifiers = [
        aws_iam_openid_connect_provider.github.arn
      ]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"

      values = [
        "sts.amazonaws.com"
      ]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"

      values = [
        "${local.github_subject_prefix}:pull_request",
        "${local.github_subject_prefix}:ref:refs/heads/main"
      ]
    }
  }
}

#grant plan role AWS read permission
resource "aws_iam_role_policy_attachment" "github_terraform_plan_readonly" {
  role       = aws_iam_role.github_terraform_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

#State Bucket need least privilegde for write
data "aws_iam_policy_document" "github_terraform_state" {

  statement {
    actions = [
      "s3:ListBucket"
    ]

    resources = [
      aws_s3_bucket.terraform_state.arn
    ]
  }

  statement {
    actions = [
      "s3:GetObject",
      "s3:PutObject"
    ]

    resources = [
      "${aws_s3_bucket.terraform_state.arn}/${local.state_key}"
    ]
  }

  statement {
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject"
    ]

    resources = [
      "${aws_s3_bucket.terraform_state.arn}/${local.state_key}.tflock"
    ]
  }
}

resource "aws_iam_role_policy" "github_terraform_state" {
  name = "finguard-terraform-state-access"

  role = aws_iam_role.github_terraform_plan.id

  policy = data.aws_iam_policy_document.github_terraform_state.json
}