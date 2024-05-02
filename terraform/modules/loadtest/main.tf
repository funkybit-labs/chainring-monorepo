data "aws_iam_policy_document" "loadtest_node_doc" {
  statement {
    actions = ["sts:AssumeRole"]
    effect  = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "loadtest_node_role" {
  name_prefix        = "${var.name_prefix}-loadtest-node-role"
  assume_role_policy = data.aws_iam_policy_document.loadtest_node_doc.json
}

resource "aws_iam_role_policy_attachment" "loadtest_node_role_policy" {
  role       = aws_iam_role.loadtest_node_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}

resource "aws_iam_instance_profile" "loadtest_node" {
  name_prefix = "${var.name_prefix}-loadtest-node-profile"
  path        = "/ecs/instance/"
  role        = aws_iam_role.loadtest_node_role.name
}

resource "aws_security_group" "loadtest" {
  vpc_id = var.vpc.id
  name   = "${var.name_prefix}-loadtest"
  ingress {
    from_port        = 22
    to_port          = 22
    protocol         = "tcp"
    cidr_blocks      = ["${var.bastion_ip}/32"]
    ipv6_cidr_blocks = []
  }

  egress {
    from_port        = 0
    to_port          = 0
    protocol         = "-1"
    cidr_blocks      = ["0.0.0.0/0"]
    ipv6_cidr_blocks = []
  }
}

data "aws_ssm_parameter" "loadtest_node_ami" {
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2/recommended/image_id"
}

resource "aws_launch_template" "loadtest_ec2" {
  name_prefix            = "${var.name_prefix}-loadtest-ec2-"
  image_id               = data.aws_ssm_parameter.loadtest_node_ami.value
  instance_type          = var.instance_type
  vpc_security_group_ids = [aws_security_group.loadtest.id]
  key_name               = "loadtest-key"

  iam_instance_profile { arn = aws_iam_instance_profile.loadtest_node.arn }
  monitoring { enabled = true }

  update_default_version = true
}


resource "aws_autoscaling_group" "loadtest" {
  name_prefix         = "${var.name_prefix}-loadtest-asg-"
  vpc_zone_identifier = [var.subnet_id]
  // we only want one instance for now
  min_size              = 1
  max_size              = 1
  protect_from_scale_in = true

  launch_template {
    id      = aws_launch_template.loadtest_ec2.id
    version = "$Latest"
  }

  tag {
    key                 = "Name"
    value               = "${var.name_prefix}-loadtest-cluster"
    propagate_at_launch = true
  }
}