locals {
  http_api_port = 5337
  account_id    = data.aws_caller_identity.current.account_id
  port_mappings = [{ containerPort = local.http_api_port, hostPort = local.http_api_port, protocol = "tcp" }]
}

resource "aws_iam_role" "sequencer_task_role" {
  name_prefix        = "${var.name_prefix}-sequencer-task-role"
  assume_role_policy = data.aws_iam_policy_document.sequencer_task_doc.json
}

resource "aws_iam_role" "sequencer_exec_role" {
  name_prefix        = "${var.name_prefix}-sequencer-exec-role"
  assume_role_policy = data.aws_iam_policy_document.sequencer_task_doc.json
}

resource "aws_iam_role" "garp_exec_role" {
  name_prefix        = "${var.name_prefix}-garp-exec-role"
  assume_role_policy = data.aws_iam_policy_document.sequencer_task_doc.json
}

resource "aws_iam_role_policy" "sequencer_execution_role_policy" {
  name   = "${var.name_prefix}-sequencer-task-execution"
  role   = aws_iam_role.sequencer_exec_role.id
  policy = <<ECS_EXEC_POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    },
   {
      "Effect": "Allow",
      "Action": [
          "secretsmanager:GetSecretValue"
      ],
      "Resource": [
        "arn:aws:secretsmanager:${var.aws_region}:${local.account_id}:secret:slack-error-reporter-token-*"
      ]
    }
  ]
}
ECS_EXEC_POLICY
}

resource "aws_iam_role_policy" "garp_execution_role_policy" {
  name   = "${var.name_prefix}-garp-task-execution"
  role   = aws_iam_role.garp_exec_role.id
  policy = <<ECS_EXEC_POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    },
   {
      "Effect": "Allow",
      "Action": [
          "secretsmanager:GetSecretValue"
      ],
      "Resource": [
        "arn:aws:secretsmanager:${var.aws_region}:${local.account_id}:secret:rds!cluster-*",
        "arn:aws:secretsmanager:${var.aws_region}:${local.account_id}:secret:slack-error-reporter-token-*"
      ]
    }
  ]
}
ECS_EXEC_POLICY
}

resource "aws_iam_role_policy_attachment" "sequencer_exec_role_policy" {
  role       = aws_iam_role.sequencer_exec_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_cloudwatch_log_group" "sequencer" {
  name              = "/sequencer/${var.name_prefix}"
  retention_in_days = 14
}

resource "aws_ecs_task_definition" "sequencer" {
  family                   = "${var.name_prefix}-sequencer"
  network_mode             = "awsvpc"
  requires_compatibilities = ["EC2"]
  cpu                      = floor(var.baregate_cpu / 2)
  memory                   = floor(var.baregate_memory / 2)
  execution_role_arn       = aws_iam_role.sequencer_exec_role.arn
  task_role_arn            = aws_iam_role.sequencer_task_role.arn
  volume {
    name      = "queues"
    host_path = "/data/queues"
  }
  container_definitions = jsonencode([
    {
      name         = "${var.name_prefix}-sequencer"
      image        = "${local.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/sequencer:latest"
      essential    = true
      portMappings = []
      cpu          = 0
      volumesFrom  = []
      environment  = []
      secrets      = []
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group" : aws_cloudwatch_log_group.sequencer.name
          "awslogs-region" : var.aws_region,
          "awslogs-stream-prefix" : "${var.name_prefix}-sequencer",
          "mode" : "non-blocking"
        }
      }
      command = ["sequencer"]
      mountPoints = [
        {
          sourceVolume  = "queues"
          containerPath = "/data/queues"
        }
      ]
    },
  ])
}


resource "aws_ecs_task_definition" "gateway_and_response_processor" {
  family                   = "${var.name_prefix}-garp"
  network_mode             = "awsvpc"
  requires_compatibilities = ["EC2"]
  cpu                      = floor(var.baregate_cpu / 2)
  memory                   = floor(var.baregate_memory / 2)
  execution_role_arn       = aws_iam_role.garp_exec_role.arn
  task_role_arn            = aws_iam_role.sequencer_task_role.arn
  volume {
    name      = "queues"
    host_path = "/data/queues"
  }
  container_definitions = jsonencode([
    {
      name         = "${var.name_prefix}-garp"
      image        = "${local.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/sequencer:latest"
      essential    = true
      portMappings = local.port_mappings
      cpu          = 0
      volumesFrom  = []
      environment  = []
      secrets      = []
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group" : aws_cloudwatch_log_group.sequencer.name
          "awslogs-region" : var.aws_region,
          "awslogs-stream-prefix" : "${var.name_prefix}-garp",
          "mode" : "non-blocking"
        }
      }
      command = ["not-sequencer"]
      mountPoints = [
        {
          sourceVolume  = "queues"
          containerPath = "/data/queues"
        }
      ]
    },
  ])
}

resource "aws_security_group" "security_group" {
  name   = "${var.name_prefix}-sequencer-ecs-task"
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
  from_port   = local.http_api_port
  to_port     = local.http_api_port
  protocol    = "tcp"
  cidr_blocks = [var.vpc.cidr_block]
}

resource "aws_service_discovery_service" "sequencer" {
  name = "${var.name_prefix}-sequencer"

  dns_config {
    namespace_id = var.service_discovery_private_dns_namespace.id

    dns_records {
      ttl  = 300
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }
}

resource "aws_ecs_service" "sequencer" {
  name                               = "${var.name_prefix}-sequencer"
  cluster                            = var.ecs_cluster_name
  task_definition                    = aws_ecs_task_definition.sequencer.arn
  desired_count                      = 1
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    security_groups = []
    subnets         = [var.subnet_id]
  }

  capacity_provider_strategy {
    capacity_provider = var.capacity_provider_name
    base              = 1
    weight            = 100
  }

  ordered_placement_strategy {
    type  = "spread"
    field = "attribute:ecs.availability-zone"
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
}


resource "aws_ecs_service" "garp" {
  name                               = "${var.name_prefix}-garp"
  cluster                            = var.ecs_cluster_name
  task_definition                    = aws_ecs_task_definition.gateway_and_response_processor.arn
  desired_count                      = 1
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    security_groups = [aws_security_group.security_group.id]
    subnets         = [var.subnet_id]
  }

  capacity_provider_strategy {
    capacity_provider = var.capacity_provider_name
    base              = 1
    weight            = 100
  }

  ordered_placement_strategy {
    type  = "spread"
    field = "attribute:ecs.availability-zone"
  }

  service_registries {
    registry_arn = aws_service_discovery_service.sequencer.arn
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
}
