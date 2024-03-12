variable "name_prefix" {}
variable "subnet_id_1" {}
variable "subnet_id_2" {}
variable "instance_class" {
  default = "db.r5.large"
}
variable "security_groups" {
  type = list(string)
  default = []
}
variable "vpc" {}