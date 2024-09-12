module "waf" {
  source                    = "../modules/waf"
  aws_region                = var.aws_region
  aws_lb_arn                = data.terraform_remote_state.api.outputs.lb_arn
  short_env_name            = var.short_env_name
  ipv4_blacklist_addresses  = local.ipv4blacklist
  ipv4_whitelist_addresses  = local.ipv4whitelist
  ipv6_blacklist_addresses  = local.ipv6blacklist
  ipv6_whitelist_addresses  = local.ipv6whitelist
  blocked_country_codes     = var.blocked_country_codes
}






