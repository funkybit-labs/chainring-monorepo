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
  default = "10.40"
}

data "terraform_remote_state" "shared" {
  backend = "s3"
  config = {
    bucket = "funkybit-terraform-state"
    key    = "shared/main.tfstate"
    region = var.aws_region
  }
}

data "aws_acm_certificate" "funkybit_fun_us_east_1" {
  domain    = "*.funkybit.fun"
  key_types = ["EC_prime256v1"]
  provider  = aws.us_east_1
}

data "aws_acm_certificate" "funkybit_fun" {
  domain    = "*.funkybit.fun"
  key_types = ["EC_prime256v1"]
}

variable "blocked_country_codes" {
  default = [
    "US", #United States
  ]
}

variable "ip_dos_rate_limit" {
  default = 5000
}


data "dns_a_record_set" "nlb_ips" {
  host = "${module.alb.dns_name}"
}

data "dns_a_record_set" "nlb_mocker_ips" {
  host = "${module.alb.dns_name}"
}

data "dns_a_record_set" "tm_app_ips" {
  host = "${module.telegram_mini_app.dns_name}"
}

locals {

  ipv4whitelist = [
        join(",", data.dns_a_record_set.nlb_ips.addrs),
        join(",", data.dns_a_record_set.tm_app_ips.addrs)
   ]

  ipv4blacklist = []

  ipv6whitelist = []

  ipv6blacklist = []

}

terraform {
  required_version = "1.5.7"

  backend "s3" {
    bucket = "funkybit-terraform-state"
    key    = "testnet/main.tfstate"
    region = "us-east-2"
  }
}
