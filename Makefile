.PHONY: contracts

contracts:
	./gradlew compileContractsAndGenerateWrappers

anvil_image:
	cd docker/anvil && docker build -t anvil -f ./Dockerfile .&& cd ../..

stop_containers:
	docker-compose down --remove-orphans

start_containers: stop_containers
	docker-compose down --remove-orphans && docker-compose up -d

anvil_logs:
	docker compose logs anvil -f

local_containers:
	./gradlew jibDockerBuild

publish_containers:
	AWS_SDK_LOAD_CONFIG=true ./gradlew jib

db_migrate:
	./gradlew :run --args="db:migrate"

db_seed:
	./gradlew :run --args="db:seed"

local_init: start_containers contracts db_migrate db_seed

lint:
	./gradlew lintKotlin && cd web-ui && pnpm run lint

format:
	./gradlew formatKotlin && cd web-ui && pnpm run lint --fix

test:
	./gradlew test

run_backend:
	./gradlew :run

run_ui:
	cd web-ui && pnpm install && pnpm run dev

