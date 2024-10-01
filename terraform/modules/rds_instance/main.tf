resource "aws_db_subnet_group" "db_subnet_group" {
  name       = "${var.name_prefix}-db-instance-subnet-group"
  subnet_ids = [var.subnet_id_1, var.subnet_id_2]
}

resource "aws_security_group" "db_security_group" {
  vpc_id      = var.vpc.id
  name        = "${var.name_prefix}-db-instance-security-group"
  description = "${var.name_prefix} DB instance security group"

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = var.security_groups
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_instance" "db_instance" {
  identifier     = "${var.name_prefix}-db-instance"
  engine         = "postgres"
  engine_version = "16.3"
  instance_class = var.instance_class

  manage_master_user_password = true
  username                    = "funkybit"
  db_name                     = "funkybit"
  db_subnet_group_name        = aws_db_subnet_group.db_subnet_group.name

  storage_type      = "gp3"
  storage_encrypted = true
  allocated_storage = var.allocated_storage

  # enable standby db instance ready for failover
  multi_az = true

  backup_retention_period = 7
  maintenance_window      = "Mon:08:00-Mon:08:30"
  skip_final_snapshot     = true
  vpc_security_group_ids  = [aws_security_group.db_security_group.id]

  # Enhanced advanced monitoring (CPU, I/O). '0' interval is disabled
  monitoring_interval = var.enable_advanced_monitoring ? 60 : 0
  monitoring_role_arn = var.enable_advanced_monitoring ? aws_iam_role.rds_monitoring_role[0].arn : null

  # Enable performance insights (query level)
  performance_insights_enabled          = var.enable_performance_insights
  performance_insights_retention_period = var.enable_performance_insights ? var.performance_insights_retention_days : null

  tags = {
    Name = "${var.name_prefix}-db-instance"
  }
}

resource "aws_iam_role" "rds_monitoring_role" {
  count = var.enable_advanced_monitoring ? 1 : 0
  name  = "${var.name_prefix}-rds-instance-monitoring-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = "sts:AssumeRole",
        Effect = "Allow",
        Principal = {
          Service = "monitoring.rds.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring_role_attachment" {
  count      = var.enable_advanced_monitoring ? 1 : 0
  role       = aws_iam_role.rds_monitoring_role[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

locals {
  account_id = data.aws_caller_identity.current.account_id
}

resource "aws_secretsmanager_secret_policy" "ci_db_password_access" {
  depends_on = [aws_db_instance.db_instance]
  secret_arn = aws_db_instance.db_instance.master_user_secret.0.secret_arn
  policy     = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
          "secretsmanager:GetSecretValue"
      ],
      "Principal": {
        "AWS": "${var.ci_role_arn}"
      },
      "Resource": [
        "${aws_db_instance.db_instance.master_user_secret.0.secret_arn}"
      ]
    }
  ]
}
EOF
}