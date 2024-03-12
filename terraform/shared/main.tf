resource "aws_ecr_repository" "backend" {
  name                 = "backend"
  image_tag_mutability = "MUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_repository" "anvil" {
  name                 = "anvil"
  image_tag_mutability = "MUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

moved {
  from = aws_ecr_repository.ecr
  to   = aws_ecr_repository.backend
}

resource "aws_route53_zone" "zone" {
  name = var.zone
}