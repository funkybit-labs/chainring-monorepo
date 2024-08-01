variable "aws_region" {}
variable "cidr_prefix" {}
variable "create_public" {
  default = true
}
variable "enable_dns_hostnames" {
  default = true
}
variable "zone" {
  default = "funkybit.fun"
}
variable "name_prefix" {}