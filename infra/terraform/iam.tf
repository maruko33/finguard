#-----------------------Trust Policy -------------------------
data "aws_iam_policy_document" "ecs_task_assume_role" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

#-----------------------Task Execution role -------------------------
resource "aws_iam_role" "ecs_execution" {
  name               = "finguard-ecs-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json
}
###Execution policy
resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

#-----------------------Java Transaction API Task Role-------------------------
resource "aws_iam_role" "transaction_api_task" {
  name               = "finguard-transaction-api-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json
}
###Priviledge
data "aws_iam_policy_document" "transaction_api" {
  statement {
    effect = "Allow"

    actions = [
      "sqs:SendMessage"
    ]

    resources = [
      aws_sqs_queue.transactions.arn
    ]
  }
}

#下面这一段的作用正是把写好的权限内容（Policy）直接绑定（嵌入）到指定的角色（Role）上。

resource "aws_iam_role_policy" "transaction_api" {
  name   = "finguard-transaction-api-policy"
  role   = aws_iam_role.transaction_api_task.id
  policy = data.aws_iam_policy_document.transaction_api.json
}


#-----------------------Python Risk Worker Lambda Role-------------------------
data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "risk_worker_lambda" {
  name               = "finguard-risk-worker-lambda-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

#-----------------------ECS Execution Role for grabbing secret-------------------------
data "aws_iam_policy_document" "ecs_execution_secrets" {
  statement {
    effect = "Allow"

    actions = [
      "secretsmanager:GetSecretValue"
    ]

    resources = [
      aws_db_instance.postgres.master_user_secret[0].secret_arn
    ]
  }
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name   = "finguard-ecs-secrets-policy"
  role   = aws_iam_role.ecs_execution.id
  policy = data.aws_iam_policy_document.ecs_execution_secrets.json
}

#-----------------SQS IAM role policy---------------------
resource "aws_iam_role_policy" "lambda_sqs" {
  name = "finguard-lambda-sqs-policy"
  role = aws_iam_role.risk_worker_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = aws_sqs_queue.transactions.arn
      }
    ]
  })
}

#-----------------SQS policy DOcument ------------------
data "aws_iam_policy_document" "risk_worker" {
  statement {
    effect = "Allow"

    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes"
    ]

    resources = [
      aws_sqs_queue.transactions.arn
    ]
  }
}