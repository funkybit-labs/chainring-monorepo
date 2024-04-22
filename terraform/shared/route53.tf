resource "aws_route53_record" "mx1" {
  zone_id = aws_route53_zone.zone.zone_id
  name    = ""
  type    = "MX"
  ttl     = "3600"
  records = ["1 smtp.google.com"]
}

resource "aws_route53_record" "txt1" {
  zone_id = aws_route53_zone.zone.zone_id
  name    = ""
  type    = "TXT"
  ttl     = "3600"
  records = ["1password-site-verification=56NVOBHTXJHU5A3ZVNGH2H7FXM",
    "atlassian-domain-verification=Y0P7zKXxIaRKMfHBLklUwVWj8CmqLh/MtxdFMl21vJ6w1WHtvyC/4Ch5TbytJ2E4",
    "google-site-verification=Cc88b5Qxy61HOJBvk06J5I3lr_eve-2i2was2VrQiKA",
  ]
}

resource "aws_route53_record" "ssl-cname" {
  zone_id = aws_route53_zone.zone.zone_id
  name    = "_3AC1C83F5B961E277F9F8D420B913530"
  type    = "CNAME"
  ttl     = "3600"
  records = ["39F0435706010FC8CBE2AF55CBEC8554.9F8D87F56D6B3A493E9C12FACFB037DC.65ef58b9431fe.comodoca.com"]
}

resource "aws_route53_record" "finance-mx" {
  zone_id = aws_route53_zone.zone-finance.zone_id
  name    = ""
  type    = "MX"
  ttl     = "3600"
  records = ["1 smtp.google.com"]
}

resource "aws_route53_record" "finance-txt" {
  name    = ""
  type    = "TXT"
  ttl     = "3600"
  zone_id = aws_route53_zone.zone-finance.zone_id
  records = ["google-site-verification=qSxGq86doLnvngSJ_g-yZWyRIQjY26KhTOf1R8wk3Os", ]
}

resource "aws_route53_record" "labs-mx" {
  zone_id = aws_route53_zone.zone-labs.zone_id
  name    = ""
  type    = "MX"
  ttl     = "3600"
  records = ["1 smtp.google.com"]
}

resource "aws_route53_record" "labs-txt" {
  zone_id = aws_route53_zone.zone-labs.zone_id
  name    = ""
  type    = "TXT"
  ttl     = "3600"
  records = ["google-site-verification=893C55w6EUTpqzu3FSaZL2YAocPa7zXU2VQ2UTnyVwM",
  ]
}

resource "aws_route53_record" "docs-cname" {
  name    = "docs"
  type    = "CNAME"
  ttl     = "3600"
  zone_id = aws_route53_zone.zone-finance.zone_id
  records = ["9a4e771167-hosting.gitbook.io", ]
}

resource "aws_route53_record" "ssl-verify-finance" {
  name    = "_A38B8661E0E01630849D50A757A267D0"
  type    = "CNAME"
  ttl     = "300"
  zone_id = aws_route53_zone.zone-finance.zone_id
  records = ["72512A224FA3B67B9E29EC0BE438AFE8.BFED610B43600D3C6EEFF06AEF3B062D.6622a32583b41.comodoca.com", ]
}
