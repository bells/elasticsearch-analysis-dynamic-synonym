image_host ?=

image_tag ?= 1.0.0

.PHONY: help build_image push_image build_image_and_push

help: ## Display this help message.
	@echo "Please use \`make <target>\` where <target> is one of"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; \
	{printf "\033[36m%-40s\033[0m %s\n", $$1, $$2}'

build_image:  ## Build docker image. Use `image_host` to override the default image host and `image_tag` to do the same for image tag.
	docker build -t $(image_host)dynamic-synonym-elasticsearch:$(image_tag) -t $(image_host)dynamic-synonym-elasticsearch:latest .

push_image:  ## Push docker image. Use `image_host` to override the default image host.
	docker push -a $(image_host)dynamic-synonym-elasticsearch

build_image_and_push: build_image push_image  ## Build and push docker image. Use `image_host` to override the default image host and `image_tag` to do the same for image tag.