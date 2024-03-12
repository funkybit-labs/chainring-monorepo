resource "aws_key_pair" "deployer" {
  key_name   = "deployer-key"
  public_key = var.deployer_key
}

resource "aws_security_group" "bastion" {
  vpc_id = var.vpc.id
  name   = "${var.name_prefix}-bastion"

  # allow SSH from anywhere
  ingress {
    from_port        = 22
    to_port          = 22
    protocol         = "tcp"
    cidr_blocks      = ["0.0.0.0/0"]
    ipv6_cidr_blocks = ["::/0"]
  }

  egress {
    from_port        = 0
    to_port          = 0
    protocol         = "-1"
    cidr_blocks      = ["0.0.0.0/0"]
    ipv6_cidr_blocks = ["::/0"]
  }
}

resource "aws_instance" "bastion" {
  instance_type               = var.instance_type
  ami                         = var.ami
  associate_public_ip_address = true
  subnet_id                   = var.subnet_id
  key_name                    = aws_key_pair.deployer.key_name
  user_data_base64            = base64encode(templatefile("${path.module}/user_data.sh.tftpl", { ssh_authorized_keys = base64encode(join("\n", var.user_keys)) }))
  user_data_replace_on_change = true
  vpc_security_group_ids      = [aws_security_group.bastion.id]
}

resource "aws_route53_record" "dns" {
  type    = "A"
  ttl     = "300"
  name    = "${var.name_prefix}-bastion.${var.zone.name}"
  records = [aws_instance.bastion.public_ip]
  zone_id = var.zone.zone_id
}
