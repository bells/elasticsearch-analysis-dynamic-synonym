FROM maven:3.9.9-eclipse-temurin-17-alpine AS build

RUN apk add --no-cache unzip
WORKDIR /app
COPY pom.xml LICENSE NOTICE ./
COPY src ./src
RUN mvn --batch-mode clean verify \
    && unzip target/releases/elasticsearch-analysis-dynamic-synonym-*.zip -d target/extracted

FROM docker.elastic.co/elasticsearch/elasticsearch:8.7.1
COPY --from=build --chown=elasticsearch:elasticsearch /app/target/extracted /usr/share/elasticsearch/plugins/dynamic-synonym/
