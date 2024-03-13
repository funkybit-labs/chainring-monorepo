variable "github_org" {
  description = "Name of GitHub organization/user (case sensitive)"
  default = "Chainring-Inc"
}

variable "repository_name" {
  description = "Name of GitHub repository (case sensitive)"
  default = "chainring-monorepo"
}

variable "oidc_provider_arn" {
  description = "Arn for the GitHub OIDC Provider."
  default     = ""
}

variable "oidc_audience" {
  description = "Audience supplied to configure-aws-credentials."
  default     = "sts.amazonaws.com"
}
