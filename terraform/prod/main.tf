module "vpc" {
  source      = "../modules/vpc"
  aws_region  = var.aws_region
  cidr_prefix = var.cidr_prefix
}
