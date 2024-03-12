resource "aws_lightsail_instance" "lightsail" {
  name              = var.lightsail_instance_name
  availability_zone = var.lightsail_availability_zone
  ip_address_type   = var.lightsail_ip_address_type
  blueprint_id      = var.lightsail_blueprint
  bundle_id         = var.lightsail_bundle_id

  add_on {
    type          = "AutoSnapshot"
    snapshot_time = "06:00"
    status        = "Enabled"
  }
}

resource "aws_lightsail_static_ip" "lightsail" {
  name = var.lightsail_static_ip_name
}

resource "aws_lightsail_static_ip_attachment" "lightsail" {
  static_ip_name = aws_lightsail_static_ip.lightsail.id
  instance_name  = aws_lightsail_instance.lightsail.id
}

resource "aws_lightsail_key_pair" "default_key_pair" {
  name       = "importing"
  public_key = var.lightsail_publickey
}
