output "cluster" {
  value = aws_ecs_cluster.cluster
}

output "app_ecs_task_role" {
  value = aws_iam_role.app_ecs_task_role
}