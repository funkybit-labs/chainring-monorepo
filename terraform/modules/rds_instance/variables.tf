variable "name_prefix" {}
variable "subnet_id_1" {}
variable "subnet_id_2" {}
variable "instance_class" {
  default = "db.r5d.large"
}
variable "allocated_storage" {
  default = "500"
}
variable "security_groups" {
  type    = list(string)
  default = []
}
variable "vpc" {}
variable "aws_region" {}
variable "ci_role_arn" {}
data "aws_caller_identity" "current" {}
variable "enable_advanced_monitoring" {
  description = "Enable enhanced monitoring for the RDS instance"
  type        = bool
  default     = false
}
variable "enable_performance_insights" {
  description = "Enable Performance Insights for the RDS instance"
  type        = bool
  default     = false
}
variable "performance_insights_retention_days" {
  description = "The amount of time in days to retain Performance Insights data"
  type        = number
  default     = 7
}