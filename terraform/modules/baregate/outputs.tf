output "capacity_provider_name" {
  value = aws_ecs_capacity_provider.main.name
}

output "baregate_cpu" {
  value = data.aws_ec2_instance_type.instance_type.default_vcpus * 1024
}

output "baregate_memory" {
  value = data.aws_ec2_instance_type.instance_type.memory_size
}
