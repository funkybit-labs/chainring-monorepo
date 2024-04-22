locals {
  name_prefix = "testnet"
  zone        = data.terraform_remote_state.shared.outputs.finance_zone
  zone_name   = data.terraform_remote_state.shared.outputs.finance_zone.name
}

module "vpc" {
  source      = "../modules/vpc"
  name_prefix = local.name_prefix
  aws_region  = var.aws_region
  cidr_prefix = var.cidr_prefix
}

module "alb" {
  source      = "../modules/alb"
  name_prefix = local.name_prefix
  subnet_id_1 = module.vpc.public_subnet_id_1
  subnet_id_2 = module.vpc.public_subnet_id_2
  vpc         = module.vpc.vpc
}

module "ecs" {
  source      = "../modules/ecs"
  name_prefix = local.name_prefix
  vpc_id      = module.vpc.vpc.id
}

module "api" {
  source                                  = "../modules/ecs_task"
  name_prefix                             = local.name_prefix
  task_name                               = "api"
  image                                   = "backend"
  ecs_cluster_id                          = module.ecs.cluster.id
  app_ecs_task_role                       = module.ecs.app_ecs_task_role
  aws_region                              = var.aws_region
  subnet_id_1                             = module.vpc.private_subnet_id_1
  subnet_id_2                             = module.vpc.private_subnet_id_2
  vpc                                     = module.vpc.vpc
  allow_inbound                           = true
  hostnames                               = ["${local.name_prefix}-api.${local.zone_name}"]
  lb_https_listener_arn                   = module.alb.https_listener_arn
  lb_dns_name                             = module.alb.dns_name
  zone                                    = local.zone
  tcp_ports                               = [9000]
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
}

module "bastion" {
  source      = "../modules/bastion"
  name_prefix = local.name_prefix
  vpc         = module.vpc.vpc
  subnet_id   = module.vpc.public_subnet_id_1
  zone        = local.zone
}

module "rds" {
  source          = "../modules/rds"
  name_prefix     = local.name_prefix
  subnet_id_1     = module.vpc.private_subnet_id_1
  subnet_id_2     = module.vpc.private_subnet_id_2
  instance_class  = "db.t3.large"
  security_groups = [module.api.security_group_id, module.bastion.security_group.id]
  vpc             = module.vpc.vpc
  aws_region      = var.aws_region
  ci_role_arn     = data.terraform_remote_state.shared.outputs.ci_role_arn
}

module "web" {
  source      = "../modules/web"
  name_prefix = local.name_prefix
  zone        = local.zone
  providers = {
    aws.us_east_1 = aws.us_east_1
  }
  ci_role_arn     = data.terraform_remote_state.shared.outputs.ci_role_arn
  certificate_arn = data.aws_acm_certificate.chainring_finance.arn
}

module "baregate" {
  source           = "../modules/baregate"
  name_prefix      = local.name_prefix
  subnet_id        = module.vpc.private_subnet_id_2
  vpc              = module.vpc.vpc
  ecs_cluster_name = module.ecs.cluster.name
  bastion_ip       = module.bastion.private_ip
}

module "sequencer" {
  source                                  = "../modules/sequencer"
  name_prefix                             = local.name_prefix
  aws_region                              = var.aws_region
  capacity_provider_name                  = module.baregate.capacity_provider_name
  ecs_cluster_name                        = module.ecs.cluster.name
  subnet_id                               = module.vpc.private_subnet_id_2
  vpc                                     = module.vpc.vpc
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
}