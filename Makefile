.PHONY: docker

web3j_generate:
	./gradlew web3jGenerate

anvil_image:
	docker build -t anvil -f ./docker/anvil/Dockerfile .

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

ui_server:
	cd web-ui && pnpm run dev
