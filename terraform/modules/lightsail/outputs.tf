output "lightsail_static_ip" {
  value = [ aws_lightsail_static_ip_attachment.lightsail.ip_address ]
}
