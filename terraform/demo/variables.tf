variable "aws_region" {
  default = "us-east-2"
}

provider "aws" {
  region = var.aws_region
}

provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
}

variable "cidr_prefix" {
  default = "10.10"
}

data "terraform_remote_state" "shared" {
  backend = "s3"
  config = {
    bucket = "chainring-terraform-state"
    key    = "shared/main.tfstate"
    region = var.aws_region
  }
}

data "aws_acm_certificate" "chainring_us_east_1" {
  domain    = "*.chainring.co"
  key_types = ["EC_prime256v1"]
  provider  = aws.us_east_1
}

data "aws_acm_certificate" "chainring" {
  domain    = "*.chainring.co"
  key_types = ["EC_prime256v1"]
}

terraform {
  required_version = "1.5.7"

  backend "s3" {
    bucket = "chainring-terraform-state"
    key    = "demo/main.tfstate"
    region = "us-east-2"
  }
}
