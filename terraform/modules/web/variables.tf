variable "name_prefix" {}
variable "zone" {}
variable "ci_role_arn" {}
data "aws_caller_identity" "current" {}
data "aws_acm_certificate" "chainring" {
  domain    = "*.chainring.co"
  key_types = ["EC_prime256v1"]
  provider  = aws.us_east_1
}