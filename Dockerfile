ARG ES_VERSION=9.5.4
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
ARG ES_VERSION

RUN apk add --no-cache unzip
WORKDIR /app
COPY pom.xml LICENSE NOTICE ./
COPY src ./src
RUN mvn --batch-mode -Drevision=${ES_VERSION} clean verify \
    && unzip target/releases/elasticsearch-analysis-dynamic-synonym-*.zip -d target/extracted

FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}
COPY --from=build --chown=elasticsearch:elasticsearch /app/target/extracted /usr/share/elasticsearch/plugins/analysis-dynamic-synonym/
