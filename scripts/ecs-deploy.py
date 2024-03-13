import argparse
import os
from string import Template

import boto3
import mimetypes
import yaml
import json


class ChainringDeploymentManager:
    def __init__(self, cluster_name, region_name):
        self.region_name = region_name
        self.cluster_name = cluster_name
        session = boto3.session.Session(region_name=region_name)
        self.ecs_client = session.client('ecs')
        self.s3_client = session.client('s3')
        self.elbv2_client = session.client('elbv2')


    def wait_for_stable_state(self, service_names):
        waiter = self.ecs_client.get_waiter('services_stable')
        print(f"Waiting for {service_names} to reach a stable state in region {self.region_name}...")
        waiter.wait(
            cluster=self.cluster_name,
            services=list(service_names),
            WaiterConfig={
                'Delay': 5,
                'MaxAttempts': 120
            }
        )

    def update_instances_count(self, service_names, desired_count):
        print(f"Updating {service_names} instances count to {desired_count} in region {self.region_name}")
        for service_name in service_names:
            self.ecs_client.update_service(
                cluster=self.cluster_name,
                service=service_name,
                desiredCount=desired_count
            )

    def get_latest_task_definitions(self, service_names):
        services = self.ecs_client.describe_services(
            cluster=self.cluster_name,
            services=list(service_names)
        )['services']
        task_def_arns = {service['serviceName']: service['taskDefinition']
                         for service in services}

        latest_task_defs = dict()
        for service_name, task_def_arn in task_def_arns.items():
            current_task_def = self.ecs_client.describe_task_definition(taskDefinition=task_def_arn)['taskDefinition']
            task_def_family = current_task_def['family']
            r = self.ecs_client.describe_task_definition(
                taskDefinition=task_def_family,
                include=['TAGS']
            )
            latest_task_defs[service_name] = r['taskDefinition']
            if len(r['tags']) > 0:
                latest_task_defs[service_name]['tags'] = r['tags']

        return latest_task_defs

    def update_services(self, service_names, env_name, env_config, image_tag):
        print(f"Updating {service_names} services")
        task_defs = self.get_latest_task_definitions(service_names)
        for service_name, task_def in task_defs.items():
            new_task_def = {key: task_def[key] for key in [
                'family',
                'taskRoleArn',
                'executionRoleArn',
                'networkMode',
                'containerDefinitions',
                'volumes',
                'placementConstraints',
                'requiresCompatibilities',
                'cpu',
                'memory',
                'tags',
                'pidMode',
                'ipcMode',
                'proxyConfiguration',
                'inferenceAccelerators'
            ] if key in task_def}
            primary_container = new_task_def['containerDefinitions'][0]
            service_config = env_config['services'][service_name]
            primary_container['image'] = f"{service_config['image']}:{image_tag}"

            service_env = service_config.get('environment')
            if service_env and isinstance(service_env, str) and service_env in env_config['environment']:
                service_env_vars = env_config['environment'][service_env]
                primary_container['environment'] = [
                    {'name': key, 'value': value}
                    for key, value in service_env_vars.items()
                ]
            elif isinstance(service_env, dict):
                primary_container['environment'] = [
                    {'name': key, 'value': value}
                    for key, value in service_env.items()
                ]
            else:
                primary_container['environment'] = []

            #if service_name.startswith('vault-'):
            #    primary_container['environment'].append({
            #        'name': 'ENV_NAME',
            #        'value': env_name
            #    })
            #    primary_container['environment'].append({
            #        'name': 'APP_NAME',
            #        'value': service_name.removeprefix('vault-')
            #    })

            if 'secrets' in service_config:
                primary_container['secrets'] = [
                    {'name': key, 'valueFrom': value}
                    for key, value in service_config['secrets'].items()
                ]
            else:
                primary_container['secrets'] = []

            new_task_def_arn = self.ecs_client.register_task_definition(
                **new_task_def
            )['taskDefinition']['taskDefinitionArn']
            self.ecs_client.update_service(
                cluster=self.cluster_name,
                service=service_name,
                taskDefinition=new_task_def_arn
            )

        self.update_deeplink_content(env_name, env_config)

    def switch_to_holding(self, env_name):
        print(f"Setting up holding page for environment: {env_name}")

        listener_arn = self.resolve_load_balancer_listener_arn(env_name)

        # Load JSON files for rules
        with open(f"./holding_page/api-conditions.json", 'r') as f:
            api_conditions = json.load(f)
        with open(f"./holding_page/api-actions.json", 'r') as f:
            api_actions = json.load(f)

        # Create and tag holding rule
        create_rule_response = self.elbv2_client.create_rule(
            ListenerArn=listener_arn,
            Priority=1,
            Conditions=api_conditions,
            Actions=api_actions,
        )
        rule_arn = create_rule_response['Rules'][0]['RuleArn']
        self.elbv2_client.add_tags(
            ResourceArns=[rule_arn],
            Tags=[
                {
                    'Key': 'purpose',
                    'Value': 'holding'
                }
            ]
        )

        print("Holding page rule was added successfully.")

    def switch_to_app(self, env_name):
        print(f"Removing holding page for environment: {env_name}")

        listener_arn = self.resolve_load_balancer_listener_arn(env_name)
        rules = self.elbv2_client.describe_rules(ListenerArn=listener_arn)['Rules']

        # Remove holding rule (filtering by tag 'purpose=holding')
        for rule in rules:
            tag_descriptions = self.elbv2_client.describe_tags(ResourceArns=[rule['RuleArn']])['TagDescriptions'][0]['Tags']
            if any(tag['Key'] == 'purpose' and tag['Value'] == 'holding' for tag in tag_descriptions):
                print(f"Deleting rule with 'purpose=holding': {rule['RuleArn']}")
                self.elbv2_client.delete_rule(RuleArn=rule['RuleArn'])

        print("Holding page rule was removed successfully.")

    def resolve_load_balancer_listener_arn(self, env_name):
        lb_name = f"{env_name}-lb"
        lb = self.elbv2_client.describe_load_balancers(Names=[lb_name])['LoadBalancers'][0]
        lb_arn = lb['LoadBalancerArn']
        listeners = self.elbv2_client.describe_listeners(LoadBalancerArn=lb_arn)['Listeners']
        listener_arn = next(filter(lambda l: l['Port'] == 443, listeners))['ListenerArn']
        print(f"LB ARN: {lb_arn}, Listener ARN: {listener_arn}")
        return listener_arn


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ECS service management tool")
    parser.add_argument('action', choices={'stop', 'start', 'upgrade', 'switch-to-holding', 'switch-to-app'})
    parser.add_argument('--env', required=True)
    parser.add_argument('--region', default="us-east-2", choices={'us-east-2', 'eu-central-1'})
    parser.add_argument('--services', required=False)
    parser.add_argument('--tag', default='latest')
    args = parser.parse_args()

    env_config = None
    base = os.path.dirname(__file__)
    with open(f"{base}/envs/{args.env}.yml", "r") as stream:
        try:
            env_config = yaml.safe_load(stream)
        except yaml.YAMLError as exc:
            print(exc)
    cluster = f"{args.env}-cluster"

    service_manager = ChainringDeploymentManager(cluster_name=cluster, region_name=args.region)

    services = set(env_config['services'].keys())

    if args.services:
        services = services.intersection(set(x.strip() for x in args.services.split(",")))

    # services in ECS are prefixed with env
    services = {f"{args.env}-{service}" for service in services}

    if len(services) > 0:
        if args.action == 'start':
            service_manager.wait_for_stable_state(services)
            for service in services:
                config_service_name = service.removeprefix(f"{args.env}-")
                service_manager.update_instances_count([service], desired_count=env_config['services'][config_service_name]['count'])
            service_manager.wait_for_stable_state(services)
            service_manager.switch_to_app(args.env)
        elif args.action == 'stop':
            service_manager.switch_to_holding(args.env)
            service_manager.update_instances_count(services, desired_count=0)
            service_manager.wait_for_stable_state(services)
        elif args.action == 'upgrade':
            print("UPGRADE")
            service_manager.wait_for_stable_state(services)
            service_manager.update_services(services, args.env, env_config, args.tag)
            service_manager.wait_for_stable_state(services)

    if args.action == 'switch-to-holding':
        service_manager.switch_to_holding(args.env)
    if args.action == 'switch-to-app':
        service_manager.switch_to_app(args.env)
