variable "aws_region" {
  description = "AWS Deployment region.."
  default     = "us-east-2"
}

variable "aws_lb_arn" {
  description = "ARN of your LoadBalance that you want to attach with WAF.."
}

variable "short_env_name" {}

variable "ipv4_blacklist_addresses" {
  description = "Blacklisted IPv4 addresses"
}

variable "ipv6_blacklist_addresses" {
  description = "Blacklisted IPv6 addresses"
}

variable "ipv4_whitelist_addresses" {
  description = "Whitelisted IPv4 addresses"
}

variable "ipv6_whitelist_addresses" {
  description = "Whitelisted IPv6 addresses"
}

variable "blocked_country_codes" {
  description = "Naughty list."
}

variable "ip_dos_rate_limit" {
  description = "How many requests per IP address."
}