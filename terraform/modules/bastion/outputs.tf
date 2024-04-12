output "security_group" {
  value = aws_security_group.bastion
}

output "deployer_key_name" {
  value = aws_key_pair.deployer.key_name
}

output "private_ip" {
  value = aws_instance.bastion.private_ip
}