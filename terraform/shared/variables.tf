variable "aws_region" {
  default = "us-east-2"
}

provider "aws" {
  region = var.aws_region
}

variable "cidr_prefix" {
  default = "10.30"
}

terraform {
  required_version = "1.5.7"

  backend "s3" {
    bucket = "chainring-terraform-state"
    key    = "shared/main.tfstate"
    region = "us-east-2"
  }
}
