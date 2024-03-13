.PHONY: docker

web3j_generate:
	./gradlew web3jGenerate

anvil:
	docker build -t anvil -f ./docker/anvil/Dockerfile .

docker:
	docker-compose down --remove-orphans && docker-compose up -d

local_containers:
	./gradlew jibDockerBuild

publish_containers:
	AWS_SDK_LOAD_CONFIG=true ./gradlew jib
