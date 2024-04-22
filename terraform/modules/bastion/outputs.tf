output "security_group" {
  value = aws_security_group.bastion
}

output "private_ip" {
  value = aws_instance.bastion.private_ip
}