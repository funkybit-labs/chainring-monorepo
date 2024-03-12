
anvil:
	docker build -t foundry-anvil -f ./anvil/Dockerfile .

docker:
	docker-compose down --remove-orphans && docker-compose up -d

local_containers:
	./gradlew jibDockerBuild

publish_containers:
	AWS_PROFILE=devops AWS_SDK_LOAD_CONFIG=true ./gradlew jib
