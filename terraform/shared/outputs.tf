output "zone" {
  value = aws_route53_zone.zone
}

output "finance_zone" {
  value = aws_route53_zone.zone-finance
}

output "labs_zone" {
  value = aws_route53_zone.zone-labs
}

output "ci_role_arn" {
  value = module.github_oidc.role.arn
}
