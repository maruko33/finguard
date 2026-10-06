#--------------------RDS.tf is subnet group for DB----------------------------
resource "aws_db_subnet_group" "main" {
  name = "finguard-db-subnet-group"

  subnet_ids = [
    aws_subnet.db_private[0].id,
    aws_subnet.db_private[1].id
  ]

  tags = {
    Name = "finguard-db-subnet-group"
  }
}

#-------------------Create RDS PostgreSQL -----------------------------------
resource "aws_db_instance" "postgres" {
  identifier = "finguard-postgres"

  engine         = "postgres"
  engine_version = "17"

  instance_class    = "db.t4g.micro"
  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = var.db_name
  username = var.db_username

  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  publicly_accessible = false
  #*****Since DB don't have egress, not going to public internet*****
  multi_az = false
  #****our network design is Multi-AZ ready, but now we don't pay for Multi-AZ standby
  #****architecture supports High Avaibility ≠ development environment 必须启用所有 HA 付费能力
  skip_final_snapshot = true
  #****in learning stage will destroy frequently

  tags = {
    Name = "finguard-postgres"
  }
}