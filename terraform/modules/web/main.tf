locals {
  account_id       = data.aws_caller_identity.current.account_id
  web_s3_origin_id = "web-origin-id"
  domain_name      = "${var.name_prefix}.${var.zone.name}"
}

resource "aws_s3_bucket" "web" {
  bucket = "${var.name_prefix}-chainring-web"
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "web" {
  origin {
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
    origin_id                = local.web_s3_origin_id
    origin_path              = ""
  }

  enabled             = true
  is_ipv6_enabled     = true
  price_class         = "PriceClass_All"
  default_root_object = "index.html"
  retain_on_delete    = false
  wait_for_deployment = true

  aliases = [local.domain_name]

  default_cache_behavior {
    allowed_methods  = ["GET", "HEAD"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = local.web_s3_origin_id

    forwarded_values {
      query_string = false

      cookies {
        forward = "none"
      }
    }

    viewer_protocol_policy = "allow-all"
    min_ttl                = 0
    default_ttl            = 3600
    max_ttl                = 86400
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    ssl_support_method             = "sni-only"
    minimum_protocol_version       = "TLSv1.2_2021"
    acm_certificate_arn            = data.aws_acm_certificate.chainring.arn
    cloudfront_default_certificate = false
  }
}

resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.bucket

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = concat(
      [{
        Sid    = "AllowCloudFrontServicePrincipalReadOnly",
        Effect = "Allow",
        Principal = {
          "Service" : "cloudfront.amazonaws.com"
        },
        Action = [
          "s3:GetObject"
        ],
        Resource = [
          "arn:aws:s3:::${aws_s3_bucket.web.id}/*"
        ],
        Condition = {
          "StringEquals" : {
            "AWS:SourceArn" : "arn:aws:cloudfront::${local.account_id}:distribution/${aws_cloudfront_distribution.web.id}"
          }
        }
      }]
    )
  })
}

resource "aws_route53_record" "dns" {
  zone_id = var.zone.zone_id
  name    = local.domain_name
  type    = "CNAME"
  ttl     = "300"
  records = [aws_cloudfront_distribution.web.domain_name]
}