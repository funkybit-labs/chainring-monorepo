variable "name_prefix" {}
variable "subnet_id_1" {}
variable "subnet_id_2" {}
variable "instance_class" {
  default = "db.r5.large"
}
variable "security_groups" {
  type    = list(string)
  default = []
}
variable "vpc" {}
variable "aws_region" {}
variable "ci_role_arn" {}
data aws_caller_identity "current" {}