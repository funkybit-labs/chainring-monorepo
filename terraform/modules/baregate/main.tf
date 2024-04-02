data "aws_iam_policy_document" "baregate_node_doc" {
  statement {
    actions = ["sts:AssumeRole"]
    effect  = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "baregate_node_role" {
  name_prefix        = "${var.name_prefix}-baregate-node-role"
  assume_role_policy = data.aws_iam_policy_document.baregate_node_doc.json
}

resource "aws_iam_role_policy_attachment" "baregate_node_role_policy" {
  role       = aws_iam_role.baregate_node_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}

resource "aws_iam_instance_profile" "baregate_node" {
  name_prefix = "${var.name_prefix}-baregate-node-profile"
  path        = "/ecs/instance/"
  role        = aws_iam_role.baregate_node_role.name
}

resource "aws_security_group" "baregate" {
  vpc_id = var.vpc.id
  name   = "${var.name_prefix}-baregate"

  egress {
    from_port        = 0
    to_port          = 0
    protocol         = "-1"
    cidr_blocks      = ["0.0.0.0/0"]
    ipv6_cidr_blocks = []
  }
}

data "aws_ssm_parameter" "baregate_node_ami" {
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2/recommended/image_id"
}

resource "aws_launch_template" "baregate_ec2" {
  name_prefix            = "${var.name_prefix}-baregate-ec2-"
  image_id               = data.aws_ssm_parameter.baregate_node_ami.value
  instance_type          = var.instance_type
  vpc_security_group_ids = [aws_security_group.baregate.id]

  iam_instance_profile { arn = aws_iam_instance_profile.baregate_node.arn }
  monitoring { enabled = true }

  user_data = base64encode(<<-EOF
      #!/bin/bash
      echo ECS_CLUSTER=${var.ecs_cluster_name} >> /etc/ecs/ecs.config;
    EOF
  )
}

resource "aws_autoscaling_group" "ecs" {
  name_prefix           = "${var.name_prefix}-baregate-asg-"
  vpc_zone_identifier   = [var.subnet_id]
  // to use an EC2 instance from ECS, it needs to be created by an autoscaling group
  // we only want one instance though
  min_size              = 1
  max_size              = 1
  protect_from_scale_in = true

  launch_template {
    id      = aws_launch_template.baregate_ec2.id
    version = "$Latest"
  }

  tag {
    key                 = "Name"
    value               = "${var.name_prefix}-baregate-cluster"
    propagate_at_launch = true
  }

  tag {
    key                 = "AmazonECSManaged"
    value               = ""
    propagate_at_launch = true
  }
}

resource "aws_ecs_capacity_provider" "main" {
  name = "${var.name_prefix}-baregate-ec2"

  auto_scaling_group_provider {
    auto_scaling_group_arn         = aws_autoscaling_group.ecs.arn
    managed_termination_protection = "DISABLED"

    managed_scaling {
      maximum_scaling_step_size = 1
      minimum_scaling_step_size = 1
      status                    = "ENABLED"
      target_capacity           = 100
    }
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = var.ecs_cluster_name
  capacity_providers = [aws_ecs_capacity_provider.main.name]

  default_capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.main.name
    base              = 1
    weight            = 100
  }
}