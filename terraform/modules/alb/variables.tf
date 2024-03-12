variable "name_prefix" {}
variable "vpc" {}
variable "subnet_id_1" {}
variable "subnet_id_2" {}
variable "health_check" {
  default = "/health"
}
data "aws_caller_identity" "current" {}
data "aws_acm_certificate" "chainring" {
  domain    = "*.chainring.co"
  key_types = ["EC_prime256v1"]
}