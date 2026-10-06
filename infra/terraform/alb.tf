#------------------ALB------------------------------
resource "aws_lb" "transaction_api" {
  name               = "finguard-alb"
  internal           = false
  load_balancer_type = "application"

  security_groups = [
    aws_security_group.alb.id
  ]

  subnets = aws_subnet.public[*].id

  tags = {
    Name = "finguard-alb"
  }
}

#-----------------Target Group-------------------------
resource "aws_lb_target_group" "transaction_api" {
  name        = "finguard-api-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  health_check {
    enabled             = true
    path                = "/api/v1/health"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "finguard-api-tg"
  }
}

#------------------Listener-----------------------------
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.transaction_api.arn

  port     = 80
  protocol = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.transaction_api.arn
  }
}