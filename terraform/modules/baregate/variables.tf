variable "subnet_id" {}
variable "instance_type" {
  default = "i3en.large"
}
variable "vpc" {}
variable "name_prefix" {}
variable "ecs_cluster_name" {}
variable "bastion_ip" {}