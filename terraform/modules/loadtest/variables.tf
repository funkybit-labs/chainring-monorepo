variable "subnet_id" {}
variable "instance_type" {
  default = "t3.large"
}
variable "vpc" {}
variable "name_prefix" {}
variable "bastion_ip" {}