variable "subnet_id" {}
variable "instance_type" {
  default = "i3en.large"
}
data "aws_ec2_instance_type" "instance_type" {
  instance_type = var.instance_type
}
variable "vpc" {}
variable "name_prefix" {}
variable "ecs_cluster_name" {}
variable "bastion_ip" {}