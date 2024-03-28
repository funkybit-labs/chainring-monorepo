variable "subnet_id" {}
variable "instance_type" {
  default = "i3en.large"
}
variable "deployer_key" {
  default = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE7/1w7LSANgOrUQ1gSpwk+vJfc2vDAkOQHCFdHpg0uR deployer-key@chainring"
}
variable "vpc" {}
variable "name_prefix" {}
variable "deployer_key_name" {}
variable "ecs_cluster_name" {}
