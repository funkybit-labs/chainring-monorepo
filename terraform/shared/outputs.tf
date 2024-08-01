output "zone" {
  value = aws_route53_zone.zone
}

output "ci_role_arn" {
  value = module.github_oidc.role.arn
}

output "icons_bucket_arn" {
  value = aws_s3_bucket.icons.arn
}