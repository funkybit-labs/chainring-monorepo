variable "subnet_id" {}
variable "instance_type" {
  default = "i3en.large"
}
variable "baregate_key" {
  default = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMQDAKaDVS7aPVdcl9JLZu7Int0uM8PU1f34aKo4GoM1 baregate-key@chainring"
}
variable "vpc" {}
variable "name_prefix" {}
variable "deployer_key_name" {}
variable "ecs_cluster_name" {}
variable "bastion_ip" {}