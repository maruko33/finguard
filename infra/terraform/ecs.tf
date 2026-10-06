#----------------ECS Cluster --------------------
resource "aws_ecs_cluster" "main" {
  name = "finguard-cluster"

  tags = {
    Name = "finguard-cluster"
  }
}

#----------------Cloud Watch Log Group --------------------
resource "aws_cloudwatch_log_group" "transaction_api" {
  name              = "/ecs/finguard-transaction-api"
  retention_in_days = 7
}

resource "aws_cloudwatch_log_group" "risk_worker" {
  name              = "/ecs/finguard-risk-worker"
  retention_in_days = 7
}


#-------------Transaction API Task Definition------------
resource "aws_ecs_task_definition" "transaction_api" {
  family                   = "finguard-transaction-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"

  cpu    = "256"
  memory = "1024"

  execution_role_arn = aws_iam_role.ecs_execution.arn
  task_role_arn      = aws_iam_role.transaction_api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "transaction-api"
      image     = "${aws_ecr_repository.transaction_api.repository_url}:${var.image_tag}"
      essential = true

      portMappings = [
        {
          containerPort = 8080
          hostPort      = 8080
          protocol      = "tcp"
        }
      ]

      environment = [
        {
          name  = "DB_URL"
          value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/${var.db_name}"
        },
        {
          name  = "DB_USERNAME"
          value = var.db_username
        },
        {
          name  = "SQS_QUEUE_URL"
          value = aws_sqs_queue.transactions.url
        },
        {
          name  = "AWS_REGION"
          value = var.aws_region
        }
      ]

      secrets = [
        {
          name = "DB_PASSWORD"

          valueFrom = "${aws_db_instance.postgres.master_user_secret[0].secret_arn}:password::"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"

        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.transaction_api.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

#-----------------Risk Worker Task Definition------------------------
resource "aws_ecs_task_definition" "risk_worker" {
  family                   = "finguard-risk-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"

  cpu    = "256"
  memory = "512"

  execution_role_arn = aws_iam_role.ecs_execution.arn
  task_role_arn      = aws_iam_role.risk_worker_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "risk-worker"
      image     = "${aws_ecr_repository.risk_worker.repository_url}:${var.image_tag}"
      essential = true

      environment = [
        {
          name  = "SQS_QUEUE_URL"
          value = aws_sqs_queue.transactions.url
        },
        {
          name  = "AWS_DEFAULT_REGION"
          value = var.aws_region
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"

        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.risk_worker.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

#----------------------Transaction_api ECS service--------------------------
resource "aws_ecs_service" "transaction_api" {
  name            = "finguard-transaction-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.transaction_api.arn

  launch_type   = "FARGATE"
  desired_count = 1

  network_configuration {
    subnets = aws_subnet.app_private[*].id

    security_groups = [
      aws_security_group.ecs_api.id
    ]

    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.transaction_api.arn
    container_name   = "transaction-api"
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.http
  ]
}

#----------------------Risk worker ECS service--------------------------
resource "aws_ecs_service" "risk_worker" {
  name            = "finguard-risk-worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.risk_worker.arn

  launch_type   = "FARGATE"
  desired_count = 1

  network_configuration {
    subnets = aws_subnet.app_private[*].id

    security_groups = [
      aws_security_group.ecs_worker.id
    ]

    assign_public_ip = false
  }
}