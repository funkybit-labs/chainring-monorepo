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
variable "tcp_ports" {
  type    = list(number)
  default = []
}
variable "include_command" {
  default = false
}
variable "image" {}
variable "ecs_cluster_id" {}
variable "subnet_id_1" {}
variable "subnet_id_2" {}
variable "allow_inbound" {
  default = false
}
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
variable "health_check" {
  default = "/health"
}
variable "health_check_status" {
  default = "200"
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
data "aws_caller_identity" "current" {}