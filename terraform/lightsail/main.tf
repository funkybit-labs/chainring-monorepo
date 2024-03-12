module "lightsail" {
  source = "../modules/lightsail"
  lightsail_instance_name = var.lightsail_instance_name
  lightsail_availability_zone = var.lightsail_availability_zone
  lightsail_ip_address_type = var.lightsail_ip_address_type
  lightsail_blueprint = var.lightsail_blueprint
  lightsail_bundle_id = var.lightsail_bundle_id
  lightsail_static_ip_name = var.lightsail_static_ip_name
}