variable "aws_region" {
  default = "us-east-2"
}

provider "aws" {
  region = var.aws_region
}

variable "cidr_prefix" {
  default = "10.30"
}

variable "chainring_zone" {
  default = "chainring.co"
}

variable "baregate_key" {
  default = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMQDAKaDVS7aPVdcl9JLZu7Int0uM8PU1f34aKo4GoM1 baregate-key@chainring"
}

variable "deployer_key" {
  default = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE7/1w7LSANgOrUQ1gSpwk+vJfc2vDAkOQHCFdHpg0uR deployer-key@chainring"
}

variable "loadtest_key" {
  default = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP8nY8Ix9r2Rh2Lxj0Kk9gQ0TupmCR4e+Sgh86sEPjUO loadtest-key@chainring"
}


terraform {
  required_version = "1.5.7"

  backend "s3" {
    bucket = "funkybit-terraform-state"
    key    = "shared/main.tfstate"
    region = "us-east-2"
  }
}
