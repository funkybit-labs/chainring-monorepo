output "public_subnet_id_1" {
  value = aws_subnet.public_subnet[0].id
}

output "public_subnet_id_2" {
  value = aws_subnet.public_subnet_2[0].id
}

output "private_subnet_id_1" {
  value = aws_subnet.private_subnet.id
}

output "private_subnet_id_2" {
  value = aws_subnet.private_subnet_2.id
}

output "vpc" {
  value = aws_vpc.vpc
}

output "service_discovery_private_dns_namespace" {
  value = aws_service_discovery_private_dns_namespace.private
}