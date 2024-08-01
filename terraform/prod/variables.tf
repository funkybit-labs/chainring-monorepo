variable "aws_region" {
  default = "eu-central-2"
}

provider "aws" {
  region = var.aws_region
}

provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
}

variable "cidr_prefix" {
  default = "10.20"
}

terraform {
  required_version = "1.5.7"

  backend "s3" {
    bucket = "funkybit-terraform-state"
    key    = "prod/main.tfstate"
    region = "us-east-2"
  }
}
