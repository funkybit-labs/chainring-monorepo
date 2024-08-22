resource "aws_iam_role" "ecs_task_execution_role" {
  name = "${var.name_prefix}-${var.task_name}-task-execution"

  assume_role_policy = <<EOF
{
 "Version": "2012-10-17",
 "Statement": [
   {
     "Action": "sts:AssumeRole",
     "Principal": {
       "Service": [
         "ecs-tasks.amazonaws.com"
       ]
     },
     "Effect": "Allow",
     "Sid": ""
   }
 ]
}
EOF
}

resource "aws_iam_role_policy" "ecs_execution_role_policy" {
  name = "${var.name_prefix}-${var.task_name}-task-execution"
  role = aws_iam_role.ecs_task_execution_role.id
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "ecr:GetAuthorizationToken",
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ],
        Resource = "*"
      }
    ]
  })
}

resource "aws_security_group" "security_group" {
  name   = "${var.name_prefix}-${var.task_name}-ecs-task"
  vpc_id = var.vpc.id
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group_rule" "ecs_task_egress" {
  security_group_id = aws_security_group.security_group.id

  type             = "egress"
  protocol         = "-1"
  from_port        = 0
  to_port          = 0
  cidr_blocks      = ["0.0.0.0/0"]
  ipv6_cidr_blocks = ["::/0"]
}

resource "aws_security_group_rule" "security_group_ingress" {
  security_group_id = aws_security_group.security_group.id

  type        = "ingress"
  from_port   = var.api_port
  to_port     = var.api_port
  protocol    = "tcp"
  cidr_blocks = [var.vpc.cidr_block]
}


resource "aws_lb_target_group" "target_group" {
  name                 = "${var.name_prefix}-${var.task_name}-tg"
  port                 = var.api_port
  protocol             = "HTTP"
  vpc_id               = var.vpc.id
  target_type          = "ip"
  deregistration_delay = 5

  health_check {
    healthy_threshold   = "2"
    interval            = "10"
    protocol            = "HTTP"
    matcher             = "200"
    timeout             = "3"
    path                = "/api/v1/prices"
    unhealthy_threshold = "3"
  }
}

resource "aws_lb_listener_rule" "target_group" {
  listener_arn = var.lb_https_listener_arn
  priority     = var.lb_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.target_group.arn
  }

  condition {
    host_header {
      values = var.hostnames
    }
  }
}

resource "aws_ecs_task_definition" "task" {
  family                   = "${var.name_prefix}-${var.task_name}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn
  task_role_arn            = var.app_ecs_task_role.arn
  container_definitions = jsonencode([
    merge(
      {
        name         = "${var.name_prefix}-${var.task_name}-db"
        image        = "mariadb:10.5.8"
        essential    = true
        portMappings = [
          { containerPort = 3306, hostPort = 3306, protocol = "tcp" }
        ]
        cpu          = 0
        volumesFrom  = []
        environment  = [
          { name = "MYSQL_DATABASE", value = "mempool" },
          { name = "MYSQL_USER", value = "mempool" },
          { name = "MYSQL_PASSWORD", value = "mempool" },
          { name = "MYSQL_ROOT_PASSWORD", value = "admin" },
        ]
        secrets      = []
        logConfiguration = {
          logDriver = "awslogs"
          options = {
            "awslogs-group" : "firelens-container",
            "awslogs-region" : var.aws_region,
            "awslogs-create-group" : "true",
            "awslogs-stream-prefix" : "${var.name_prefix}-${var.task_name}-db",
            "mode" : "non-blocking"
          }
        }
      },
      var.mount_efs_volume ? {
        mountPoints = [
          {
            "containerPath" : "/var/lib/mysql",
            "sourceVolume" : "${var.name_prefix}-${var.task_name}-efs-volume"
          }
        ]
      } : { mountPoints = [] }
    ),
    merge(
      {
        name         = "${var.name_prefix}-${var.task_name}-backend"
        image        = "mempool/backend:latest"
        essential    = true
        portMappings = [
          { containerPort = 8999, hostPort = 8999, protocol = "tcp" }
        ]
        cpu          = 0
        volumesFrom  = []
        environment  = [
          { name = "MEMPOOL_BACKEND", value = "electrum" },
          { name = "ELECTRUM_HOST", value = var.bitcoin_host },
          { name = "ELECTRUM_PORT", value = tostring(var.bitcoin_fulcrum_port) },
          { name = "ELECTRUM_TLS_ENABLED", value = "false" },
          { name = "CORE_RPC_HOST", value = var.bitcoin_host },
          { name = "CORE_RPC_PORT", value = tostring(var.bitcoin_rpc_port) },
          { name = "CORE_RPC_USERNAME", value = "user" },
          { name = "CORE_RPC_PASSWORD", value = "password" },
          { name = "DATABASE_ENABLED", value = "true" },
          { name = "DATABASE_HOST", value = "localhost" },
          { name = "DATABASE_DATABASE", value = "mempool" },
          { name = "DATABASE_USERNAME", value = "mempool" },
          { name = "DATABASE_PASSWORD", value = "mempool" },
          { name = "STATISTICS_ENABLED", value = "true" },
        ]
        command = ["./wait-for-it.sh", "localhost:3306", "--timeout=30", "--strict", "--", "./start.sh"]
        secrets      = []
        logConfiguration = {
          logDriver = "awslogs"
          options = {
            "awslogs-group" : "firelens-container",
            "awslogs-region" : var.aws_region,
            "awslogs-create-group" : "true",
            "awslogs-stream-prefix" : "${var.name_prefix}-${var.task_name}-backend",
            "mode" : "non-blocking"
          }
        }
      },
      var.mount_efs_volume ? {
        mountPoints = [
          {
            "containerPath" : "/backend/cache",
            "sourceVolume" : "${var.name_prefix}-${var.task_name}-efs-volume"
          }
        ]
      } : { mountPoints = [] }
    ),
    {
      name         = "${var.name_prefix}-${var.task_name}-frontend"
      image        = "mempool/frontend:latest"
      essential    = true
      portMappings = [
        { containerPort = var.api_port, hostPort = var.api_port, protocol = "tcp" }
      ]
      cpu          = 0
      volumesFrom  = []
      environment  = [
        { name = "FRONTEND_HTTP_PORT", value = tostring(var.api_port) },
        { name  = "BACKEND_MAINNET_HTTP_HOST", value = "localhost" }
      ]
      command = ["./wait-for", "localhost:3306", "--timeout=30", "--", "nginx", "-g", "daemon off;"]
      secrets      = []
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group" : "firelens-container",
          "awslogs-region" : var.aws_region,
          "awslogs-create-group" : "true",
          "awslogs-stream-prefix" : "${var.name_prefix}-${var.task_name}-frontend",
          "mode" : "non-blocking"
        }
      }
    }
  ])
  dynamic "volume" {
    for_each = aws_efs_file_system.task_efs_file_system
    content {
      name = "${var.name_prefix}-${var.task_name}-efs-volume"
      efs_volume_configuration {
        file_system_id     = aws_efs_file_system.task_efs_file_system[0].id
        root_directory     = "/"
        transit_encryption = "ENABLED"
      }
    }
  }
}

resource "aws_service_discovery_service" "service_discovery_service" {
  name = "${var.name_prefix}-${var.task_name}"

  dns_config {
    namespace_id = var.service_discovery_private_dns_namespace.id

    dns_records {
      ttl  = 300
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }
}

resource "aws_ecs_service" "service" {
  name                               = "${var.name_prefix}-${var.task_name}"
  cluster                            = var.ecs_cluster_id
  task_definition                    = aws_ecs_task_definition.task.arn
  desired_count                      = 0 # supposed to be updated outside of TF during each deploy
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent

  network_configuration {
    security_groups = [
      aws_security_group.security_group.id
    ]
    subnets = [
      var.subnet_id_1,
      var.subnet_id_2
    ]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.target_group.arn
    container_name   = "${var.name_prefix}-${var.task_name}-frontend"
    container_port   = var.api_port
  }

  service_registries {
    registry_arn = aws_service_discovery_service.service_discovery_service.arn
  }

  lifecycle {
    # we are updating desired count via ecs-deploy script
    ignore_changes = [desired_count]
  }
}

resource "aws_route53_record" "dns" {
  for_each = toset(var.hostnames)
  zone_id  = var.zone.zone_id
  name     = each.value
  type     = "CNAME"
  ttl      = "300"
  records  = [var.lb_dns_name]
}

resource "aws_efs_file_system" "task_efs_file_system" {
  count = var.mount_efs_volume ? 1 : 0

  creation_token = "${var.name_prefix}-${var.task_name}-efs"
}

resource "aws_security_group" "task_efs_security_group" {
  count = var.mount_efs_volume ? 1 : 0

  name   = "${var.name_prefix}-${var.task_name}-efs-sg"
  vpc_id = var.vpc.id
}

resource "aws_security_group_rule" "task_efs_security_group_egress" {
  count = var.mount_efs_volume ? 1 : 0

  security_group_id = aws_security_group.task_efs_security_group[0].id
  type              = "egress"
  protocol          = "-1"
  from_port         = 0
  to_port           = 0
  cidr_blocks       = ["0.0.0.0/0"]
  ipv6_cidr_blocks  = ["::/0"]
}

resource "aws_security_group_rule" "task_efs_security_group_ingress" {
  count = var.mount_efs_volume ? 1 : 0

  security_group_id = aws_security_group.task_efs_security_group[0].id
  type              = "ingress"
  from_port         = 2049
  to_port           = 2049
  protocol          = "tcp"
  cidr_blocks       = [var.vpc.cidr_block]
}

resource "aws_efs_mount_target" "task_efs_mount_target_subnet_1" {
  count = var.mount_efs_volume ? 1 : 0

  file_system_id  = aws_efs_file_system.task_efs_file_system[0].id
  subnet_id       = var.subnet_id_1
  security_groups = [aws_security_group.task_efs_security_group[0].id]
}

resource "aws_efs_mount_target" "task_efs_mount_target_subnet_2" {
  count = var.mount_efs_volume ? 1 : 0

  file_system_id  = aws_efs_file_system.task_efs_file_system[0].id
  subnet_id       = var.subnet_id_2
  security_groups = [aws_security_group.task_efs_security_group[0].id]
}
