.PHONY: contracts anvil_image otterscan_image start_containers stop_containers

contracts:
	./gradlew compileContractsAndGenerateWrappers

anvil_image:
	cd docker/anvil && docker build -t anvil -f ./Dockerfile .&& cd ../..

otterscan_image:
	cd docker/otterscan && docker build -t otterscan -f ./Dockerfile .&& cd ../..

stop_containers:
	docker-compose down --remove-orphans

start_containers: stop_containers
	docker-compose down --remove-orphans && docker-compose up -d

start_ci_containers: stop_containers
	docker-compose -f ./docker-compose-ci.yaml down --remove-orphans && docker-compose -f ./docker-compose-ci.yaml up -d

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
	./gradlew lintKotlin && (cd web-ui && pnpm run lint) && (cd telegram-mini-app && pnpm run lint)

format:
	./gradlew formatKotlin && (cd web-ui && pnpm run lint --fix) && (cd telegram-mini-app && pnpm run lint --fix) && (cd terraform && terraform fmt -recursive)

test:
	./gradlew test

run_backend:
	./gradlew :run

run_sequencer:
	SANDBOX_MODE=true ./gradlew :sequencer:run

run_only_sequencer:
	SANDBOX_MODE=true ./gradlew :sequencer:run --args=sequencer

run_not_sequencer:
	SANDBOX_MODE=true ./gradlew :sequencer:run --args=not-sequencer


run_mocker:
	API_URL=http://localhost:9000 ./gradlew :mocker:run

run_telegrambot:
	API_URL=http://localhost:9000 ./gradlew :telegrambot:run

run_ui:
	cd web-ui && pnpm install && pnpm run dev

