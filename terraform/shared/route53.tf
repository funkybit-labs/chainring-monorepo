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

resource "aws_route53_record" "ssl-verify-labs" {
  name    = "_E1E178EE3F2B77ED78F72E43390E6B97"
  type    = "CNAME"
  ttl     = "300"
  zone_id = aws_route53_zone.zone-labs.zone_id
  records = ["6AB4DA2AACD91439399198CE41F99198.E4B1F4E180C8DBFA922101EA8A179C22.662bd4742044d.comodoca.com", ]
}

resource "aws_route53_record" "alt-web-hostname" {
  name    = "www.chainringlabs.com"
  type    = "CNAME"
  ttl     = "300"
  zone_id = aws_route53_zone.zone-labs.zone_id
  records = ["www.chainring.co", ]
}

data "dns_a_record_set" "chainring-apex" {
  host = "chainring.co"
}

resource "aws_route53_record" "apex-labs" {
  zone_id = aws_route53_zone.zone-labs.zone_id
  name    = "chainringlabs.com"
  type    = "A"
  ttl     = "300"
  records = ["${data.dns_a_record_set.chainring-apex.addrs.0}"]
}

resource "aws_route53_record" "fb-fun-a" {
  zone_id = aws_route53_zone.fb-fun.zone_id
  name    = "funkybit.fun"
  type    = "A"
  ttl     = "300"
  records = ["${data.dns_a_record_set.chainring-apex.addrs.0}"]
}

resource "aws_route53_record" "fb-fun-mx" {
  zone_id = aws_route53_zone.fb-fun.zone_id
  name    = ""
  type    = "MX"
  ttl     = "3600"
  records = ["1 smtp.google.com"]
}

resource "aws_route53_record" "fb-fun-cname1" {
  zone_id = aws_route53_zone.fb-fun.zone_id
  name    = "www.funkybit.fun"
  type    = "CNAME"
  ttl     = "300"
  records = ["funkybit.fun"]
}

resource "aws_route53_record" "fb-fun-cname2" {
  zone_id = aws_route53_zone.fb-fun.zone_id
  name    = "w3dev.funkybit.fun"
  type    = "CNAME"
  ttl     = "300"
  records = ["funkybit.fun"]
}

resource "aws_route53_record" "fb-fun-cname3" {
  zone_id = aws_route53_zone.fb-fun.zone_id
  name    = "docs.funkybit.fun"
  type    = "CNAME"
  ttl     = "300"
  records = ["9a4e771167-hosting.gitbook.io"]
}

resource "aws_route53_record" "fb-fun-txt1" {
  zone_id = aws_route53_zone.fb-fun.zone_id
  name    = ""
  type    = "TXT"
  ttl     = "3600"
  records = ["google-site-verification=FWrje7XGlau98ClwegSSUopNXOJwMgri2D8-Zv4PHDg"]
}


resource "aws_route53_record" "fb-co-a" {
  zone_id = aws_route53_zone.fb-co.zone_id
  name    = "funkybit.co"
  type    = "A"
  ttl     = "300"
  records = ["${data.dns_a_record_set.chainring-apex.addrs.0}"]
}

resource "aws_route53_record" "fb-co-mx" {
  zone_id = aws_route53_zone.fb-co.zone_id
  name    = ""
  type    = "MX"
  ttl     = "3600"
  records = ["1 smtp.google.com"]
}

resource "aws_route53_record" "fb-xyz-a" {
  zone_id = aws_route53_zone.fb-xyz.zone_id
  name    = "funkybit.xyz"
  type    = "A"
  ttl     = "300"
  records = ["${data.dns_a_record_set.chainring-apex.addrs.0}"]
}

resource "aws_route53_record" "fb-xyz-mx" {
  zone_id = aws_route53_zone.fb-xyz.zone_id
  name    = ""
  type    = "MX"
  ttl     = "3600"
  records = ["1 smtp.google.com"]
}


resource "aws_route53_record" "fb-it-a" {
  zone_id = aws_route53_zone.fb-it.zone_id
  name    = "funkyb.it"
  type    = "A"
  ttl     = "300"
  records = ["${data.dns_a_record_set.chainring-apex.addrs.0}"]
}

resource "aws_route53_record" "fb-it-mx" {
  zone_id = aws_route53_zone.fb-it.zone_id
  name    = ""
  type    = "MX"
  ttl     = "3600"
  records = ["1 smtp.google.com"]
}
