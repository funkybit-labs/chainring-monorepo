locals {
  bootnode_rpc_port = 9001
}

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
  from_port   = local.bootnode_rpc_port
  to_port     = local.bootnode_rpc_port
  protocol    = "tcp"
  cidr_blocks = [var.vpc.cidr_block]
}


resource "aws_lb_target_group" "target_group" {
  name                 = "${var.name_prefix}-${var.task_name}-tg"
  port                 = local.bootnode_rpc_port
  protocol             = "HTTP"
  vpc_id               = var.vpc.id
  target_type          = "ip"
  deregistration_delay = 5

  health_check {
    healthy_threshold   = "2"
    interval            = "10"
    protocol            = "HTTP"
    matcher             = "405" # Arch returns HTTP status 405 (method not allowed) for non-POST requests
    timeout             = "3"
    path                = "/"
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
        name         = "${var.name_prefix}-${var.task_name}-bootnode"
        image        = "ghcr.io/arch-network/node:latest"
        essential    = true
        portMappings = [
          { containerPort = local.bootnode_rpc_port, hostPort = local.bootnode_rpc_port, protocol = "tcp" }
        ]
        cpu          = 0
        volumesFrom  = []
        environment  = [
          { name = "LOG_LEVEL", value = "debug" },
          { name = "RUST_BACKTRACE", value = "1" },
          { name = "NETWORK_MODE", value = "devnet" },
          { name = "PRIVATE_KEY_PASSWORD", value = "" },
          { name = "RPC_BIND_IP", value = "0.0.0.0" },
          { name = "BITCOIN_RPC_ENDPOINT", value = var.bitcoin_host },
          { name = "BITCOIN_RPC_PORT", value = tostring(var.bitcoin_rpc_port) },
          { name = "BITCOIN_RPC_USERNAME", value = "user" },
          { name = "BITCOIN_RPC_PASSWORD", value = "password" },
          { name = "BITCOIN_RPC_WALLET", value = "testwallet" },
          { name = "RISC0_DEV_MODE", value = "1" },
          { name = "DATA_DIR", value = "/arch-data/bootnode" },
          { name = "IS_BOOT_NODE", value = "true" },
          { name = "RPC_BIND_PORT", value = tostring(local.bootnode_rpc_port) },
          { name = "ARCH_NODES", value = "http://localhost:${local.bootnode_rpc_port},http://localhost:9002,http://localhost:9003" },
          { name = "PROVER_ENDPOINT", value = "http://localhost:8001" },
        ]
        secrets      = []
        logConfiguration = {
          logDriver = "awslogs"
          options = {
            "awslogs-group" : "firelens-container",
            "awslogs-region" : var.aws_region,
            "awslogs-create-group" : "true",
            "awslogs-stream-prefix" : "${var.name_prefix}-${var.task_name}-bootnode",
            "mode" : "non-blocking"
          }
        }
      },
      var.mount_efs_volume ? {
        mountPoints = [
          {
            "containerPath" : "/arch-data",
            "sourceVolume" : "${var.name_prefix}-${var.task_name}-efs-volume"
          }
        ]
      } : { mountPoints = [] }
    ),
    merge(
      {
        name         = "${var.name_prefix}-${var.task_name}-node"
        image        = "ghcr.io/arch-network/node:latest"
        essential    = true
        portMappings = [
          { containerPort = 9002, hostPort = 9002, protocol = "tcp" }
        ]
        cpu          = 0
        volumesFrom  = []
        environment  = [
          { name = "LOG_LEVEL", value = "debug" },
          { name = "RUST_BACKTRACE", value = "1" },
          { name = "NETWORK_MODE", value = "devnet" },
          { name = "PRIVATE_KEY_PASSWORD", value = "" },
          { name = "RPC_BIND_IP", value = "0.0.0.0" },
          { name = "BITCOIN_RPC_ENDPOINT", value = var.bitcoin_host },
          { name = "BITCOIN_RPC_PORT", value = tostring(var.bitcoin_rpc_port) },
          { name = "BITCOIN_RPC_USERNAME", value = "user" },
          { name = "BITCOIN_RPC_PASSWORD", value = "password" },
          { name = "BITCOIN_RPC_WALLET", value = "testwallet" },
          { name = "RISC0_DEV_MODE", value = "1" },
          { name = "DATA_DIR", value = "/arch-data/node" },
          { name = "RPC_BIND_PORT", value = "9002" },
          { name = "BOOT_NODE_ENDPOINT", value = "http://localhost:${local.bootnode_rpc_port}" },
          { name = "PROVER_ENDPOINT", value = "http://localhost:8001" },
        ]
        dependsOn = [
          { containerName = "${var.name_prefix}-${var.task_name}-bootnode", condition = "START" }
        ]
        secrets      = []
        logConfiguration = {
          logDriver = "awslogs"
          options = {
            "awslogs-group" : "firelens-container",
            "awslogs-region" : var.aws_region,
            "awslogs-create-group" : "true",
            "awslogs-stream-prefix" : "${var.name_prefix}-${var.task_name}-node",
            "mode" : "non-blocking"
          }
        }
      },
      var.mount_efs_volume ? {
        mountPoints = [
          {
            "containerPath" : "/arch-data",
            "sourceVolume" : "${var.name_prefix}-${var.task_name}-efs-volume"
          }
        ]
      } : { mountPoints = [] }
    ),
    merge(
      {
        name         = "${var.name_prefix}-${var.task_name}-prover"
        image        = "ghcr.io/arch-network/zkvm:latest"
        essential    = true
        portMappings = [
          { containerPort = 9003, hostPort = 9003, protocol = "tcp" },
          { containerPort = 8001, hostPort = 8001, protocol = "tcp" }
        ]
        cpu          = 0
        volumesFrom  = []
        environment  = [
          { name = "LOG_LEVEL", value = "debug" },
          { name = "RUST_BACKTRACE", value = "1" },
          { name = "NETWORK_MODE", value = "devnet" },
          { name = "PRIVATE_KEY_PASSWORD", value = "" },
          { name = "RPC_BIND_IP", value = "0.0.0.0" },
          { name = "BITCOIN_RPC_ENDPOINT", value = var.bitcoin_host },
          { name = "BITCOIN_RPC_PORT", value = tostring(var.bitcoin_rpc_port) },
          { name = "BITCOIN_RPC_USERNAME", value = "user" },
          { name = "BITCOIN_RPC_PASSWORD", value = "password" },
          { name = "BITCOIN_RPC_WALLET", value = "testwallet" },
          { name = "RISC0_DEV_MODE", value = "1" },
          { name = "DATA_DIR", value = "/arch-data/prover" },
          { name = "RPC_BIND_PORT", value = "9003" },
          { name = "ZKVM_RPC_BIND_IP", value = "0.0.0.0" },
          { name = "ZKVM_RPC_BIND_PORT", value = "8001" },
          { name = "ARCH_BOOT_NODE_URL", value = "http://localhost:${local.bootnode_rpc_port}" },
          { name = "PROVER_ENDPOINT", value = "http://localhost:8001" },
        ]
        dependsOn = [
          { containerName = "${var.name_prefix}-${var.task_name}-bootnode", condition = "START" }
        ]
        secrets      = []
        logConfiguration = {
          logDriver = "awslogs"
          options = {
            "awslogs-group" : "firelens-container",
            "awslogs-region" : var.aws_region,
            "awslogs-create-group" : "true",
            "awslogs-stream-prefix" : "${var.name_prefix}-${var.task_name}-prover",
            "mode" : "non-blocking"
          }
        }
      },
      var.mount_efs_volume ? {
        mountPoints = [
          {
            "containerPath" : "/arch-data",
            "sourceVolume" : "${var.name_prefix}-${var.task_name}-efs-volume"
          }
        ]
      } : { mountPoints = [] }
    ),
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
    container_name   = "${var.name_prefix}-${var.task_name}-bootnode"
    container_port   = local.bootnode_rpc_port
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
