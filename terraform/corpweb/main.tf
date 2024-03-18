module "lightsail" {
  source = "../modules/lightsail"
  instance_name = var.instance_name
  aws_region = var.aws_region
  aws_availability_zone = "${var.aws_region}a"
  ip_address_type = var.ip_address_type
  blueprint = var.blueprint
  bundle_id = var.bundle_id
  static_ip_name = var.static_ip_name
  dns_zone = var.dns_zone
  publickey-name = var.publickey-name
  publickey = var.publickey
  development_web = var.development_web
  primary_web = var.primary_web
}

