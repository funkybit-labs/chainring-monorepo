
provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  default = "us-east-2"
}

variable "aws_availability_zone" {
  default = ""
}

variable "instance_name" {
  type        = string
  description = "Name of the lightsail instance"
  default = "corpweb"
}

variable "ip_address_type" {
  type        = string
  description = "The IP address type of the Lightsail Instance. Valid Values: dualstack | ipv4"

  default = "ipv4"
}

variable "blueprint" {
  type        = string
  description = "Blueprints listed by the AWS cli - aws lightsail get-blueprints"
  default = "ubuntu_22_04"
}

variable "bundle_id" {
  type        = string
  description = "Bundles listed by the AWS cli - aws lightsail get-bundles"
  default = "small_3_0"
}

variable "static_ip_name" {
  type        = string
  description = "Unique name for the lightsail static ip resource"
  default = "corpwebip"
}

variable "dns_zone" {
  type = string
  default= "chainring.co."
}

variable "primary_web" {
  type = string
  default = "www"
}

variable "development_web" {
  type = string
  default = "w3dev"
}

variable "publickey" {
  type        = string
  description = "Public key for access lightsail instance"
  default = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIO9/S8kIxj/lD2DFO8tBWfE2QoyP0tu5AqQf52KlLj8w sburke@chainring.co"
}

variable "publickey-name" {
  type        = string
  default     = "chainring-web"
}

terraform {
  required_version = "1.5.7"

  backend "s3" {
    bucket = "chainring-terraform-state"
    key    = "lightsail/main.tfstate"
    region = "us-east-2"
  }
}