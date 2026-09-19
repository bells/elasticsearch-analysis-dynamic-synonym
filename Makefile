image_host ?=
image_tag ?= $(shell python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text)')
image_name = $(image_host)dynamic-synonym-elasticsearch:$(image_tag)

.PHONY: help build_image push_image build_image_and_push

help: ## Display available targets.
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "%-30s %s\n", $$1, $$2}'

build_image: ## Build a local image; image_tag defaults to the POM version.
	docker build -t $(image_name) .

push_image: ## Publish only the explicit image_name tag; image_host must identify the registry namespace.
	@test -n "$(image_host)" || (echo "Set image_host before publishing" >&2; exit 1)
	docker push $(image_name)

build_image_and_push: build_image ## Build then publish the explicitly selected tag.
	$(MAKE) push_image
