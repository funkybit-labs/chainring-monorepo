locals {
  name_prefix = "demo"
}

module "vpc" {
  source      = "../modules/vpc"
  name_prefix = local.name_prefix
  aws_region  = var.aws_region
  cidr_prefix = var.cidr_prefix
}

module "alb" {
  source          = "../modules/alb"
  name_prefix     = local.name_prefix
  subnet_id_1     = module.vpc.public_subnet_id_1
  subnet_id_2     = module.vpc.public_subnet_id_2
  vpc             = module.vpc.vpc
  certificate_arn = data.aws_acm_certificate.funkybit.arn
}

module "ecs" {
  source      = "../modules/ecs"
  name_prefix = local.name_prefix
  vpc_id      = module.vpc.vpc.id
}

module "api" {
  source            = "../modules/ecs_task"
  name_prefix       = local.name_prefix
  task_name         = "api"
  include_command   = true
  image             = "backend"
  cpu               = 2048
  memory            = 4096
  ecs_cluster_id    = module.ecs.cluster.id
  app_ecs_task_role = module.ecs.app_ecs_task_role
  aws_region        = var.aws_region
  subnet_id_1       = module.vpc.private_subnet_id_1
  subnet_id_2       = module.vpc.private_subnet_id_2
  vpc               = module.vpc.vpc
  allow_inbound     = true
  hostnames = [
    "${local.name_prefix}-api.${data.terraform_remote_state.shared.outputs.zone.name}"
  ]
  lb_https_listener_arn                   = module.alb.https_listener_arn
  lb_dns_name                             = module.alb.dns_name
  zone                                    = data.terraform_remote_state.shared.outputs.zone
  tcp_ports                               = [9000]
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
  icons_bucket                            = data.terraform_remote_state.shared.outputs.icons_bucket
}

module "ring" {
  source                                  = "../modules/ecs_task"
  name_prefix                             = local.name_prefix
  task_name                               = "ring"
  include_command                         = true
  image                                   = "backend"
  cpu                                     = 2048
  memory                                  = 4096
  ecs_cluster_id                          = module.ecs.cluster.id
  app_ecs_task_role                       = module.ecs.app_ecs_task_role
  aws_region                              = var.aws_region
  subnet_id_1                             = module.vpc.private_subnet_id_1
  subnet_id_2                             = module.vpc.private_subnet_id_2
  vpc                                     = module.vpc.vpc
  allow_inbound                           = false
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
}

module "telegrambot" {
  source                                  = "../modules/ecs_task"
  name_prefix                             = local.name_prefix
  task_name                               = "telegrambot"
  include_command                         = true
  image                                   = "backend"
  cpu                                     = 2048
  memory                                  = 4096
  ecs_cluster_id                          = module.ecs.cluster.id
  app_ecs_task_role                       = module.ecs.app_ecs_task_role
  aws_region                              = var.aws_region
  subnet_id_1                             = module.vpc.private_subnet_id_1
  subnet_id_2                             = module.vpc.private_subnet_id_2
  vpc                                     = module.vpc.vpc
  allow_inbound                           = false
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
}

module "anvil" {
  source            = "../modules/ecs_task"
  name_prefix       = local.name_prefix
  task_name         = "anvil"
  image             = "anvil"
  cpu               = 2048
  memory            = 16384
  ecs_cluster_id    = module.ecs.cluster.id
  app_ecs_task_role = module.ecs.app_ecs_task_role
  aws_region        = var.aws_region
  subnet_id_1       = module.vpc.private_subnet_id_1
  subnet_id_2       = module.vpc.private_subnet_id_2
  vpc               = module.vpc.vpc
  allow_inbound     = true
  hostnames = [
    "${local.name_prefix}-anvil.${data.terraform_remote_state.shared.outputs.zone.name}"
  ]
  lb_https_listener_arn                   = module.alb.https_listener_arn
  lb_priority                             = 101
  lb_dns_name                             = module.alb.dns_name
  zone                                    = data.terraform_remote_state.shared.outputs.zone
  tcp_ports                               = [8545]
  health_check                            = "/"
  health_check_status                     = "400"
  mount_efs_volume                        = true
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
  deployment_minimum_healthy_percent      = 0
  deployment_maximum_percent              = 100
}

module "otterscan" {
  source            = "../modules/ecs_task"
  name_prefix       = local.name_prefix
  task_name         = "otterscan"
  image             = "otterscan"
  ecs_cluster_id    = module.ecs.cluster.id
  app_ecs_task_role = module.ecs.app_ecs_task_role
  aws_region        = var.aws_region
  subnet_id_1       = module.vpc.private_subnet_id_1
  subnet_id_2       = module.vpc.private_subnet_id_2
  vpc               = module.vpc.vpc
  allow_inbound     = true
  hostnames = [
    "${local.name_prefix}-otterscan.${data.terraform_remote_state.shared.outputs.zone.name}"
  ]
  lb_https_listener_arn                   = module.alb.https_listener_arn
  lb_priority                             = 102
  lb_dns_name                             = module.alb.dns_name
  zone                                    = data.terraform_remote_state.shared.outputs.zone
  tcp_ports                               = [80]
  health_check                            = "/"
  health_check_status                     = "200"
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
}

module "anvil2" {
  source            = "../modules/ecs_task"
  name_prefix       = local.name_prefix
  task_name         = "anvil2"
  image             = "anvil"
  cpu               = 2048
  memory            = 16384
  ecs_cluster_id    = module.ecs.cluster.id
  app_ecs_task_role = module.ecs.app_ecs_task_role
  aws_region        = var.aws_region
  subnet_id_1       = module.vpc.private_subnet_id_1
  subnet_id_2       = module.vpc.private_subnet_id_2
  vpc               = module.vpc.vpc
  allow_inbound     = true
  hostnames = [
    "${local.name_prefix}-anvil2.${data.terraform_remote_state.shared.outputs.zone.name}"
  ]
  lb_https_listener_arn                   = module.alb.https_listener_arn
  lb_priority                             = 103
  lb_dns_name                             = module.alb.dns_name
  zone                                    = data.terraform_remote_state.shared.outputs.zone
  tcp_ports                               = [8545]
  health_check                            = "/"
  health_check_status                     = "400"
  mount_efs_volume                        = true
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
  deployment_minimum_healthy_percent      = 0
  deployment_maximum_percent              = 100
}

module "otterscan2" {
  source            = "../modules/ecs_task"
  name_prefix       = local.name_prefix
  task_name         = "otterscan2"
  image             = "otterscan"
  ecs_cluster_id    = module.ecs.cluster.id
  app_ecs_task_role = module.ecs.app_ecs_task_role
  aws_region        = var.aws_region
  subnet_id_1       = module.vpc.private_subnet_id_1
  subnet_id_2       = module.vpc.private_subnet_id_2
  vpc               = module.vpc.vpc
  allow_inbound     = true
  hostnames = [
    "${local.name_prefix}-otterscan2.${data.terraform_remote_state.shared.outputs.zone.name}"
  ]
  lb_https_listener_arn                   = module.alb.https_listener_arn
  lb_priority                             = 104
  lb_dns_name                             = module.alb.dns_name
  zone                                    = data.terraform_remote_state.shared.outputs.zone
  tcp_ports                               = [80]
  health_check                            = "/"
  health_check_status                     = "200"
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
}

module "bastion" {
  source      = "../modules/bastion"
  name_prefix = local.name_prefix
  vpc         = module.vpc.vpc
  subnet_id   = module.vpc.public_subnet_id_1
  zone        = data.terraform_remote_state.shared.outputs.zone
}


module "rds" {
  source         = "../modules/rds"
  name_prefix    = local.name_prefix
  subnet_id_1    = module.vpc.private_subnet_id_1
  subnet_id_2    = module.vpc.private_subnet_id_2
  instance_class = "db.r5.large"
  security_groups = [
    module.api.security_group_id, module.bastion.security_group.id, module.sequencer.security_group_id,
    module.telegrambot.security_group_id, module.ring.security_group_id
  ]
  vpc         = module.vpc.vpc
  aws_region  = var.aws_region
  ci_role_arn = data.terraform_remote_state.shared.outputs.ci_role_arn

  enable_advanced_monitoring          = true
  enable_performance_insights         = true
  performance_insights_retention_days = 7
}

module "web" {
  source      = "../modules/web"
  name_prefix = local.name_prefix
  zone        = data.terraform_remote_state.shared.outputs.zone
  providers = {
    aws.us_east_1 = aws.us_east_1
  }
  ci_role_arn     = data.terraform_remote_state.shared.outputs.ci_role_arn
  certificate_arn = data.aws_acm_certificate.funkybit_us_east_1.arn
}

module "telegram_mini_app" {
  source      = "../modules/telegram_mini_app"
  name_prefix = local.name_prefix
  zone        = data.terraform_remote_state.shared.outputs.zone
  providers = {
    aws.us_east_1 = aws.us_east_1
  }
  ci_role_arn     = data.terraform_remote_state.shared.outputs.ci_role_arn
  certificate_arn = data.aws_acm_certificate.funkybit_us_east_1.arn
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
  baregate_cpu                            = module.baregate.baregate_cpu
  baregate_memory                         = module.baregate.baregate_memory * 0.9
}

module "mocker" {
  source            = "../modules/ecs_task"
  name_prefix       = local.name_prefix
  task_name         = "mocker"
  image             = "mocker"
  ecs_cluster_id    = module.ecs.cluster.id
  app_ecs_task_role = module.ecs.app_ecs_task_role
  aws_region        = var.aws_region
  subnet_id_1       = module.vpc.private_subnet_id_1
  subnet_id_2       = module.vpc.private_subnet_id_2
  vpc               = module.vpc.vpc
  allow_inbound     = true
  hostnames = [
    "${local.name_prefix}-mocker.${data.terraform_remote_state.shared.outputs.zone.name}"
  ]
  lb_https_listener_arn                   = module.alb.https_listener_arn
  lb_priority                             = 105
  lb_dns_name                             = module.alb.dns_name
  zone                                    = data.terraform_remote_state.shared.outputs.zone
  tcp_ports                               = [8000]
  service_discovery_private_dns_namespace = module.vpc.service_discovery_private_dns_namespace
}

module "holding_page" {
  source      = "../modules/holding_page_lambda"
  name_prefix = local.name_prefix
  vpc         = module.vpc.vpc
}
