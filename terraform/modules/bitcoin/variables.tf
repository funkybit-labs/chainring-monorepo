variable "name_prefix" {}
variable "task_name" {}
variable "aws_region" {}
variable "vpc" {}
variable "cpu" {
  default = 1024
}
variable "memory" {
  default = 2048
}
variable "bitcoind_image" {}
variable "bitcoind_port" {
  default = 18443
}
variable "fulcrum_image" {}
variable "fulcrum_port" {
  default = 50001
}
variable "ecs_cluster_id" {}
variable "subnet_id_1" {}
variable "subnet_id_2" {}
variable "hostnames" {
  type    = list(string)
  default = []
}
variable "lb_https_listener_arn" {
  default = ""
}
variable "lb_priority" {
  default = 100
}
variable "app_ecs_task_role" {}
variable "zone" {
  default = {}
}
variable "lb_dns_name" {
  default = ""
}
variable "mount_efs_volume" {
  default = false
}
variable "service_discovery_private_dns_namespace" {}
variable "deployment_maximum_percent" {
  type    = number
  default = 200
}
variable "deployment_minimum_healthy_percent" {
  type    = number
  default = 100
}
