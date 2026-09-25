image_host ?=
plugin_version ?= $(shell python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}properties/{http://maven.apache.org/POM/4.0.0}revision").text)')
image_tag ?= $(plugin_version)
image_name = $(image_host)dynamic-synonym-elasticsearch:$(image_tag)
package_zip = target/releases/elasticsearch-analysis-dynamic-synonym-$(plugin_version).zip

.PHONY: help build_image build_image_from_zip push_image build_image_and_push

help: ## Display available targets.
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "%-30s %s\n", $$1, $$2}'

build_image: ## Build a local image; image_tag defaults to the POM version.
	docker build --build-arg ES_VERSION=$(plugin_version) -t $(image_name) .

build_image_from_zip: ## Build an image from the verified plugin ZIP without recompiling.
	@test -f $(package_zip) || (echo "Missing $(package_zip)" >&2; exit 1)
	rm -rf target/package-image
	mkdir -p target/package-image
	unzip -q $(package_zip) -d target/package-image
	docker build --build-arg ES_VERSION=$(plugin_version) -f Dockerfile.package -t $(image_name) .

push_image: ## Publish only the explicit image_name tag; image_host must identify the registry namespace.
	@test -n "$(image_host)" || (echo "Set image_host before publishing" >&2; exit 1)
	docker push $(image_name)

build_image_and_push: build_image ## Build then publish the explicitly selected tag.
	$(MAKE) push_image
