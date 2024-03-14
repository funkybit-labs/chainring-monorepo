locals {
  http_api_port = 9000
  account_id    = data.aws_caller_identity.current.account_id
  port_mappings = flatten([for port in var.tcp_ports : { containerPort = port, hostPort = port, protocol = "tcp" }])
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
  name   = "${var.name_prefix}-${var.task_name}-task-execution"
  role   = aws_iam_role.ecs_task_execution_role.id
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
        "arn:aws:secretsmanager:${var.aws_region}:${local.account_id}:secret:${var.name_prefix}/${var.task_name}/*"
      ]
    }
  ]
}
ECS_EXEC_POLICY
}

resource "aws_security_group" "security_group" {
  name   = "${var.name_prefix}-${var.task_name}-ecs-task"
  vpc_id = var.vpc.id
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
  count             = var.allow_inbound ? 1 : 0
  security_group_id = aws_security_group.security_group.id

  type        = "ingress"
  from_port   = local.http_api_port
  to_port     = local.http_api_port
  protocol    = "tcp"
  cidr_blocks = [var.vpc.cidr_block]
}


resource "aws_alb_target_group" "target_group" {
  count                = var.allow_inbound ? 1 : 0
  name                 = "${var.name_prefix}-${var.task_name}-tg"
  port                 = local.http_api_port
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
    path                = var.health_check
    unhealthy_threshold = "3"
  }
}

resource "aws_alb_listener_rule" "target_group" {
  count        = var.allow_inbound ? 1 : 0
  listener_arn = var.lb_https_listener_arn
  priority     = var.lb_priority

  action {
    type             = "forward"
    target_group_arn = aws_alb_target_group.target_group[0].arn
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
    merge({
      name         = "${var.name_prefix}-${var.task_name}"
      image        = "${local.account_id}.dkr.ecr.us-east-1.amazonaws.com/${var.image}:latest"
      essential    = true
      portMappings = local.port_mappings
      cpu          = 0
      mountPoints  = []
      volumesFrom  = []
      environment  = []
      secrets      = []
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group" : "firelens-container",
          "awslogs-region" : var.aws_region,
          "awslogs-create-group" : "true",
          "awslogs-stream-prefix" : "${var.name_prefix}-${var.task_name}",
          "mode" : "non-blocking"
        }
      }
    }, var.include_command ? { command = [var.task_name] } : {}),
  ])
}


resource "aws_ecs_service" "service" {
  name            = "${var.name_prefix}-${var.task_name}"
  cluster         = var.ecs_cluster_id
  task_definition = aws_ecs_task_definition.task.arn
  desired_count   = 0 # supposed to be updated outside of TF during each deploy
  launch_type     = "FARGATE"

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
    target_group_arn = aws_alb_target_group.target_group[0].arn
    container_name   = "${var.name_prefix}-${var.task_name}"
    container_port   = local.http_api_port
  }

  lifecycle {
    # we are updating image in the task definition and desired count every time during the deploy
    ignore_changes = [task_definition, desired_count]
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