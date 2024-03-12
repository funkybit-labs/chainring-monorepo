variable "name_prefix" {}
variable "zone" {}
data "aws_caller_identity" "current" {}
data "aws_acm_certificate" "chainring" {
  domain    = "*.chainring.co"
  key_types = ["EC_prime256v1"]
}