.PHONY: docker, anvil_image

web3j_generate:
	./gradlew web3jGenerate

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

ui_server:
	cd web-ui && pnpm run dev
