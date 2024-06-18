variable "name_prefix" {}
variable "zone" {}
variable "ci_role_arn" {}
variable "certificate_arn" {}
data "aws_caller_identity" "current" {}