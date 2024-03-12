docker_image_name=anvil

build:
	docker build --platform=linux/amd64 -t $(docker_image_name) .

package_container:
	docker build -t 851725450525.dkr.ecr.us-east-2.amazonaws.com/anvil .

push_container:
	AWS_SDK_LOAD_CONFIG=true docker push 851725450525.dkr.ecr.us-east-2.amazonaws.com/anvil:latest
