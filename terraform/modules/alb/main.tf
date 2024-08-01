locals {
  account_id = data.aws_caller_identity.current.account_id
}

resource "aws_s3_bucket" "access_logs" {
  bucket = "${var.name_prefix}-funkybit-alb-access-logs"
}

# 033677994240 is the Elastic Load Balancing account ID for us-east-2, see https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-access-logs.html
resource "aws_s3_bucket_policy" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  policy = <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::033677994240:root"
      },
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::${aws_s3_bucket.access_logs.bucket}/AWSLogs/${local.account_id}/*"
    }
  ]
}
POLICY
}

resource "aws_security_group" "lb_sg" {
  name        = "${var.name_prefix}-lb"
  description = "${var.name_prefix} Load Balancer"
  vpc_id      = var.vpc.id

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_lb" "lb" {
  name               = "${var.name_prefix}-lb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.lb_sg.id]
  subnets            = [var.subnet_id_1, var.subnet_id_2]
  idle_timeout       = 60

  enable_deletion_protection = true

  access_logs {
    bucket  = aws_s3_bucket.access_logs.id
    enabled = true
  }
}

resource "aws_lb_target_group" "tg" {
  name     = "${var.name_prefix}-lb-tg"
  port     = 443
  protocol = "HTTPS"
  vpc_id   = var.vpc.id

  health_check {
    interval            = 5
    protocol            = "HTTPS"
    path                = var.health_check
    timeout             = 2
    unhealthy_threshold = 2
    healthy_threshold   = 2
    matcher             = "200"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.lb.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = 443
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.lb.arn
  port              = "443"
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS-1-2-2017-01"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.tg.arn
  }
}
