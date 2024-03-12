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