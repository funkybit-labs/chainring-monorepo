variable "subnet_id" {}
variable "ami" {
  # Amazon Linux 2023 AMI 2023.3.20240304.0 x86_64 HVM kernel-6.1
  default = "ami-022661f8a4a1b91cf"
}
variable "instance_type" {
  default = "t2.micro"
}

variable "user_keys" {
  type = list(string)
  default = [
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM4OCE/lPa8hta76kcy6b5+qUtXsH9R5rbI6CfIjCWO6 bholzman@bhmbp16-2023",
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIECo8JAWZ7LP0U1xYRodpNbRO3zxkU6rU1BE6PD0A5Uh bflood@bfmbp16-2023",
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAyaGKbEqZSEdk1hFuCGWN7yhj5wfQQSAqkjHQDIiW5c aonyshchenko@aom1mbp.local",
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINlwrdUdiktRi8Lm7QAT1GfIVFb2nQ/FTOlDzhagxi4L imykolenko@funkybit.fun",
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF1s3MvVbgkp5p6VOmA2UCMeb2oR9c5FATM644ri9Ljy sburke@funkybit.fun",
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIq6h3epxqTxyD/KcAA4/w7p26NsQoLZ5FQeQumoiteT ci@chainring"
  ]
}
variable "vpc" {}
variable "name_prefix" {}
variable "zone" {}