#ALB Security Group
resource "aws_security_group" "alb" {
  name        = "finguard-alb-sg"
  description = "Allow public HTTP traffic to the ALB"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP from Internet"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "finguard-alb-sg"
  }
}



#-----------------------Transaction API Security Group--------------------------
resource "aws_security_group" "ecs_api" {
  name        = "finguard-ecs-api-sg"
  description = "Allow Transaction API traffic from the ALB"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Transaction API traffic from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "Allow outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "finguard-ecs-api-sg"
  }
}

#---------------------------Python Risk Worker SG-------------------------------
resource "aws_security_group" "ecs_worker" {
  name        = "finguard-ecs-worker-sg"
  description = "Outbound-only security group for Risk Worker"
  vpc_id      = aws_vpc.main.id

  egress {
    description = "Allow outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "finguard-ecs-worker-sg"
  }
}

#----------------------RDS Security Group----------------------------
resource "aws_security_group" "rds" {
  name        = "finguard-rds-sg"
  description = "Allow PostgreSQL traffic from ECS"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from ECS"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_api.id]
  }

  tags = {
    Name = "finguard-rds-sg"
  }
}

#***notice there is no egresss, DB only have traffic in VPC