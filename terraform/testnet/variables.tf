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
    "KP", # Korea, Democratic People's Republic of
    "CU", # Cuba
    "RU", # Russian Federation
    "SY", # Syrian Arab Republic
    "IR", # Iran, Islamic Republic of
    "AF", # Afghanistan
    "BY", # Belarus
    "MM", # Burma (Myanmar)
    "CF", # Central African Republic
    "CD", # Congo, Democratic Republic of
    "ET", # Ethiopia
    "IQ", # Iraq
    "LB", # Lebanon
    "LY", # Libya
    "ML", # Mali
    "NI", # Nicaragua
    "SO", # Somalia
    "SD", # Sudan
    "SS", # South Sudan
    "VE", # Venezuela, Bolivarian Republic of
    "YE", # Yemen
  ]
}

variable "blocked_country_response_code" {
  default = 451 # Unavailable For Legal Reasons
}

variable "ip_dos_rate_limit" {
  default = 5000
}

variable "ip_dos_rate_limit_response_code" {
  default = 429 # Too many requests
}


data "dns_a_record_set" "nlb_ips" {
  host = module.alb.dns_name
}

data "dns_a_record_set" "nlb_mocker_ips" {
  host = module.alb.dns_name
}

data "dns_a_record_set" "tm_app_ips" {
  host = module.telegram_mini_app.dns_name
}

data "dns_a_record_set" "web_app_ips" {
  host = "${local.name_prefix}.${local.zone_name}"
}



locals {

  ipv4whitelist = [

    for i in concat(data.dns_a_record_set.nlb_ips.addrs, data.dns_a_record_set.tm_app_ips.addrs, data.dns_a_record_set.web_app_ips.addrs) : format("%s/32", i)

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
