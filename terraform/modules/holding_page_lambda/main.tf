locals {
  task_name = "holding-page-lambda"
}

data "archive_file" "code_zip" {
  type        = "zip"
  source_dir  = "${path.module}/lambda"
  output_path = "${path.module}/lambda.zip"
}

resource "aws_iam_role" "exec_role" {
  name = "${var.name_prefix}-${local.task_name}-exec-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "lambda.amazonaws.com"
        }
      },
    ]
  })
}

resource "aws_iam_role_policy_attachment" "exec_policy" {
  role       = aws_iam_role.exec_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "holding_page_function" {
  function_name = "${var.name_prefix}-${local.task_name}-function"
  handler       = "index.handler"
  runtime       = "nodejs20.x"
  role          = aws_iam_role.exec_role.arn

  source_code_hash = filebase64sha256(data.archive_file.code_zip.output_path)

  filename = data.archive_file.code_zip.output_path
}

resource "aws_cloudwatch_log_group" "log_group" {
  name = "/aws/lambda/${aws_lambda_function.holding_page_function.function_name}"

  retention_in_days = 30
}

resource "aws_alb_target_group" "target_group" {
  name        = "${var.name_prefix}-${local.task_name}-tg"
  target_type = "lambda"
  vpc_id      = var.vpc.id
  health_check {
    enabled             = true
    healthy_threshold   = 2
    interval            = 30
    path                = "/health"
    timeout             = 5
    unhealthy_threshold = 2
    matcher             = "200"
  }
}

resource "aws_lambda_permission" "allow_elb_invoke" {
  statement_id  = "AllowELBInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.holding_page_function.function_name
  principal     = "elasticloadbalancing.amazonaws.com"
  source_arn    = aws_alb_target_group.target_group.arn
}

resource "aws_lb_target_group_attachment" "target_group_attachment" {
  depends_on = [aws_lambda_permission.allow_elb_invoke]
  target_group_arn = aws_alb_target_group.target_group.arn
  target_id        = aws_lambda_function.holding_page_function.arn
}
