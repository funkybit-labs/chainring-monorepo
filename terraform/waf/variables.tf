#variable "account_id" {}
variable "env_name" {
  default = "test"
}
variable "short_env_name" {
  default = "test"
}

variable "blocked_country_codes" {
  default = [
    "DZ", #Algeria
    "BD", #Bangladesh
    "BY", #Belarus
    "BO", #Bolivia
    "CU", #Cuba
    "IR", #Iran
    "NP", #Nepal
    "KP", #North Korea
    "RU", #Russia
    "SD", #Sudan
    "SY", #Syria
    "US", #United States
  ]
}

variable "allowed_country_codes" {
  default = [
   ]
}

data "aws_nat_gateway" "vpc_gateway" {
  id = "${data.terraform_remote_state.vpc.outputs.aws_vpc.vpc.nat}"
}

locals {

    ipv4whitelist = [
#      "${data.terraform_remote_state.vpn.outputs.vpn_ec2_public_ip}/32",
      "${data.aws_nat_gateway.vpc_gateway.public_ip}/32",
 #     "${data.terraform_remote_state.appcommon.outputs.api_eip.public_ip}/32"
    ]

    ipv4blacklist = []

    ipv6whitelist = []

    ipv6blacklist = []

 }

variable "aws_region" {
  default = "us-east-2"
}

data "terraform_remote_state" "api" {
  backend   = "s3"
  workspace = terraform.workspace

  config = {
    bucket = "funkybit-terraform-state"
    key    = "test/main.tfstate"
    region = var.aws_region
  }
}

/*data "terraform_remote_state" "appcommon" {
  backend   = "s3"
  workspace = terraform.workspace

  config = {
    bucket = "funkybit-terraform-state"
    key    = "test/main.tfstate"
    region = "us-east-1"
  }
}*/

 data "terraform_remote_state" "vpc" {
   backend   = "s3"
  workspace = terraform.workspace

  config = {
    bucket = "funkybit-terraform-state"
    key    = "test/main.tfstate"
    region = var.aws_region
  }
}

# data "terraform_remote_state" "vpn" {
#   backend   = "s3"
#   workspace = terraform.workspace
#
#   config = {
#     bucket = "funkybit-terraform-state"
#     key    = "test/main.tfstate"
#     region = var.aws_region
#   }
# }

provider "aws" {
  region = var.aws_region
}

terraform {
  required_version = "1.5.7"

  backend "s3" {
    bucket = "funkybit-terraform-state"
    key    = "test/main.tfstate"
    region = "us-east-2"
  }
}


