resource "aws_db_subnet_group" "db_subnet_group" {
  name       = "${var.name_prefix}-db-subnet-group"
  subnet_ids = [var.subnet_id]
}

resource "aws_security_group" "db_security_group" {
  name        = "${var.name_prefix}-db-security-group"
  description = "${var.name_prefix} DB security group"

  // Define ingress and egress rules as needed
}

resource "aws_db_cluster" "aurora_cluster" {
  cluster_identifier           = "aurora-cluster"
  engine                       = "aurora-postgresql"
  engine_version               = "13.3"
  master_username              = "admin"
  master_password              = "your_password"
  db_subnet_group_name         = aws_db_subnet_group.aurora_subnet_group.name
  vpc_security_group_ids       = [aws_security_group.aurora_security_group.id]
  backup_retention_period      = 7   # Modify as needed
  preferred_backup_window      = "08:00-08:30"  # Modify as needed
  backup_window                = "08:00-08:30"  # Modify as needed
  skip_final_snapshot          = true

  scaling_configuration {
    auto_pause                = true
    max_capacity              = 2
    min_capacity              = 1
    seconds_until_auto_pause = 300
  }

  tags = {
    Name = "aurora-cluster"
  }
}

resource "aws_db_instance" "aurora_instance" {
  count                     = 2  # Change to 3 to create three nodes
  identifier                = "aurora-instance-${count.index + 1}"
  instance_class            = "db.r5.large"
  cluster_identifier        = aws_db_cluster.aurora_cluster.id
  engine                    = "aurora-postgresql"
  engine_version            = "13.3"
  publicly_accessible       = false
  db_subnet_group_name      = aws_db_subnet_group.aurora_subnet_group.name
  vpc_security_group_ids    = [aws_security_group.aurora_security_group.id]
}