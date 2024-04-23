variable "name_prefix" {}
variable "vpc" {}
variable "subnet_id_1" {}
variable "subnet_id_2" {}
variable "health_check" {
  default = "/health"
}
variable "certificate_arn" {}
data "aws_caller_identity" "current" {}