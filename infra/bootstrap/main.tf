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

  transaction_api_ecr_arn = "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/finguard-transaction-api"

  transaction_api_ecs_service_arn = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:service/finguard-cluster/finguard-transaction-api"

  transaction_api_task_definition_arn = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/finguard-transaction-api:*"

  ecs_execution_role_arn = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/finguard-ecs-execution-role"

  transaction_api_task_role_arn = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/finguard-transaction-api-task-role"
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

data "aws_iam_policy_document" "github_deploy_assume" {
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
        "${local.github_subject_prefix}:environment:dev"
      ]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name = "finguard-github-deploy"

  assume_role_policy = data.aws_iam_policy_document.github_deploy_assume.json
}

data "aws_iam_policy_document" "github_deploy" {

  statement {
    sid    = "ECRAuthorization"
    effect = "Allow"

    actions = [
      "ecr:GetAuthorizationToken"
    ]

    resources = ["*"]
  }

  statement {
    sid    = "PushTransactionApiImage"
    effect = "Allow"

    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart"
    ]

    resources = [
      local.transaction_api_ecr_arn
    ]
  }
  statement {
    sid    = "ReadLoadBalancer"
    effect = "Allow"

    actions = [
      "elasticloadbalancing:DescribeLoadBalancers"
    ]

    resources = ["*"]
  }

  statement {
    sid    = "RegisterTransactionApiTaskDefinition"
    effect = "Allow"

    actions = [
      "ecs:RegisterTaskDefinition"
    ]

    resources = [
      local.transaction_api_task_definition_arn
    ]
  }

  statement {
    sid    = "ReadTaskDefinitions"
    effect = "Allow"

    actions = [
      "ecs:DescribeTaskDefinition",
      "ecs:ListTaskDefinitions"
    ]

    resources = ["*"]
  }

  statement {
    sid    = "DeployTransactionApiService"
    effect = "Allow"

    actions = [
      "ecs:DescribeServices",
      "ecs:UpdateService"
    ]

    resources = [
      local.transaction_api_ecs_service_arn
    ]
  }

  statement {
    sid    = "PassFinGuardECSTaskRoles"
    effect = "Allow"

    actions = [
      "iam:PassRole"
    ]

    resources = [
      local.ecs_execution_role_arn,
      local.transaction_api_task_role_arn
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"

      values = [
        "ecs-tasks.amazonaws.com"
      ]
    }
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name = "finguard-github-deploy"

  role = aws_iam_role.github_deploy.id

  policy = data.aws_iam_policy_document.github_deploy.json
}