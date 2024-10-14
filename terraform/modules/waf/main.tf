locals {
  common_tags = {
    terraform = "true"
  }
}

resource "aws_wafv2_web_acl" "WafWebAcl" {
  name  = "${var.short_env_name}-wafv2-web-acl"
  scope = "REGIONAL"

  default_action {
    allow {
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "WAF_Common_Protections"
    sampled_requests_enabled   = true
  }

  custom_response_body {
    key          = "RateLimitExceededBody"
    content      = jsonencode({ "message" : "Request rate limit exceeded. Please try again later." })
    content_type = "APPLICATION_JSON"
  }

  rule {
    name     = "${var.short_env_name}-AWSIPWhiteList"
    priority = 1

    action {
      allow {}
    }

    statement {
      or_statement {
        statement {
          ip_set_reference_statement {
            arn = aws_wafv2_ip_set.aws-whitelist_ipv4.arn
          }
        }
        statement {
          ip_set_reference_statement {
            arn = aws_wafv2_ip_set.aws-whitelist_ipv6.arn
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSIPWhiteList"
      sampled_requests_enabled   = true
    }
  }


  rule {
    name     = "${var.short_env_name}-AWSIPBlackList"
    priority = 2

    action {
      block {}
    }

    statement {
      or_statement {
        statement {
          ip_set_reference_statement {
            arn = aws_wafv2_ip_set.aws-blacklist_ipv4.arn
          }
        }
        statement {
          ip_set_reference_statement {
            arn = aws_wafv2_ip_set.aws-blacklist_ipv6.arn
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSIPBlackList"
      sampled_requests_enabled   = true
    }
  }


  rule {
    name     = "${var.short_env_name}AWSNaughtyCountriesRule"
    priority = 3

    action {
      block {
        custom_response {
          response_code = var.blocked_country_response_code
        }
      }

    }
    statement {
      geo_match_statement {
        country_codes = var.blocked_country_codes
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}AWSNaughtyCountries"
      sampled_requests_enabled   = true
    }
  }


  rule {
    name     = "${var.short_env_name}AWSRateBasedRuleDOS"
    priority = 5

    action {
      block {
        custom_response {
          response_code            = var.ip_dos_rate_limit_response_code
          custom_response_body_key = "RateLimitExceededBody" # Reference to the custom response body
        }
      }
    }

    statement {
      rate_based_statement {
        limit              = var.ip_dos_rate_limit
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}AWSRateBasedRuleDOS"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "${var.short_env_name}-AWSManagedRulesCommonRuleSet"
    priority = 10
    override_action {
      none {
      }
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"

        rule_action_override {
          action_to_use {
            allow {}
          }

          name = "SizeRestrictions_BODY"
        }

        rule_action_override {
          action_to_use {
            allow {}
          }

          name = "NoUserAgent_HEADER"
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSManagedRulesCommonRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "${var.short_env_name}-AWSManagedRulesLinuxRuleSet"
    priority = 11
    override_action {
      none {
      }
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesLinuxRuleSet"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSManagedRulesLinuxRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "${var.short_env_name}-AWSManagedRulesAmazonIpReputationList"
    priority = 12
    override_action {
      none {
      }
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAmazonIpReputationList"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSManagedRulesAmazonIpReputationList"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWS-AWSManagedRulesAnonymousIpList"
    priority = 13
    override_action {
      none {
      }
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAnonymousIpList"
        vendor_name = "AWS"

        rule_action_override {
          action_to_use {
            allow {}
          }

          name = "HostingProviderIPList"
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSManagedRulesAnonymousIpList"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "${var.short_env_name}-AWSManagedRulesKnownBadInputsRuleSet"
    priority = 14
    override_action {
      none {
      }
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSManagedRulesKnownBadInputsRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "${var.short_env_name}-AWSManagedRulesUnixRuleSet"
    priority = 15
    override_action {
      none {
      }
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesUnixRuleSet"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSManagedRulesUnixRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "${var.short_env_name}-AWSManagedRulesWindowsRuleSet"
    priority = 16
    override_action {
      none {
      }
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesWindowsRuleSet"
        vendor_name = "AWS"
        rule_action_override {
          action_to_use {
            allow {}
          }

          name = "WindowsShellCommands_BODY"
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSManagedRulesWindowsRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "${var.short_env_name}-AWSManagedRulesBotControlRuleSet"
    priority = 21

    override_action {
      count {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesBotControlRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.short_env_name}-AWSManagedRulesBotControlRuleSet"
      sampled_requests_enabled   = true
    }
  }


  tags = merge(
    local.common_tags, {
      customer = "${var.short_env_name}-wafv2-web-acl"
    }
  )

}

resource "aws_wafv2_ip_set" "aws-blacklist_ipv4" {
  name               = "${var.short_env_name}-blacklist-ipv4"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = var.ipv4_blacklist_addresses

  tags = {
    Name = "${var.short_env_name}-blacklist-ipv4"
  }
}

resource "aws_wafv2_ip_set" "aws-blacklist_ipv6" {
  name               = "${var.short_env_name}-blacklist-ipv6"
  scope              = "REGIONAL"
  ip_address_version = "IPV6"
  addresses          = var.ipv6_blacklist_addresses

  tags = {
    Name = "${var.short_env_name}-blacklist-ipv6"
  }
}

resource "aws_wafv2_ip_set" "aws-whitelist_ipv4" {
  name               = "${var.short_env_name}-whitelist-ipv4"
  description        = "IPv4 Allow list"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = var.ipv4_whitelist_addresses

  tags = {
    Name = "${var.short_env_name}-whitelist-ipv4"
  }
}

resource "aws_wafv2_ip_set" "aws-whitelist_ipv6" {
  name               = "${var.short_env_name}-whitelist-ipv6"
  scope              = "REGIONAL"
  ip_address_version = "IPV6"
  addresses          = var.ipv6_whitelist_addresses

  tags = {
    Name = "${var.short_env_name}-whitelist-ipv6"
  }
}

#note: the name of the following resource needs to begin with "aws-waf-logs" otherwise deployment will fail"
resource "aws_cloudwatch_log_group" "WafWebAclLoggroup" {
  name              = "aws-waf-logs-wafv2-web-acl"
  retention_in_days = 30
}

resource "aws_wafv2_web_acl_association" "WafWebAclAssociation" {
  resource_arn = var.aws_lb_arn
  web_acl_arn  = aws_wafv2_web_acl.WafWebAcl.arn
  depends_on = [
    aws_wafv2_web_acl.WafWebAcl,
    aws_cloudwatch_log_group.WafWebAclLoggroup
  ]
}

resource "aws_wafv2_web_acl_logging_configuration" "WafWebAclLogging" {
  log_destination_configs = [aws_cloudwatch_log_group.WafWebAclLoggroup.arn]
  resource_arn            = aws_wafv2_web_acl.WafWebAcl.arn
  depends_on = [
    aws_wafv2_web_acl.WafWebAcl,
    aws_cloudwatch_log_group.WafWebAclLoggroup
  ]
}


