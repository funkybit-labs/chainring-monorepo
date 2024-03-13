resource "aws_lightsail_instance" "lightsail" {
  name              = var.instance_name
  availability_zone = var.aws_availability_zone
  ip_address_type   = var.ip_address_type
  blueprint_id      = var.blueprint
  bundle_id         = var.bundle_id

  add_on {
    type          = "AutoSnapshot"
    snapshot_time = "06:00"
    status        = "Enabled"
  }
}

resource "aws_lightsail_static_ip" "lightsail" {
  name = var.static_ip_name
}

resource "aws_lightsail_static_ip_attachment" "lightsail" {
  static_ip_name = aws_lightsail_static_ip.lightsail.id
  instance_name  = aws_lightsail_instance.lightsail.id
}

resource "aws_lightsail_key_pair" "default_key_pair" {
  name       = "importing"
  public_key = var.publickey
}

resource "aws_lightsail_instance_public_ports" "openports" {
  instance_name = aws_lightsail_instance.lightsail.name

  port_info {
    protocol  = "tcp"
    from_port = 80
    to_port   = 80
  }
  port_info {
    from_port = 443
    protocol  = "tcp"
    to_port   = 443
  }
  port_info {
    from_port = 22
    protocol  = "tcp"
    to_port   = 22
  }
}

data "aws_route53_zone" "primary_hosted_zone" {
  name = var.dns_zone ## Enter your domain name here
}

resource "aws_route53_record" "domain_root" {
  zone_id = "${data.aws_route53_zone.primary_hosted_zone.zone_id}"
  name    = "${data.aws_route53_zone.primary_hosted_zone.name}"
  type    = "A"
  ttl     = "300"
  records = ["${aws_lightsail_static_ip.lightsail.ip_address}"]
}

resource "aws_route53_record" "www" {
  zone_id = "${data.aws_route53_zone.primary_hosted_zone.zone_id}"
  name    = "www.${data.aws_route53_zone.primary_hosted_zone.name}"
  type    = "A"
  ttl     = "300"
  records = ["${aws_lightsail_static_ip.lightsail.ip_address}"]
}