FROM maven:3.9.2-eclipse-temurin-8-alpine as build

RUN apk update \
    && apk add zip

WORKDIR /app

COPY . ./

RUN mvn package \
    && unzip target/releases/elasticsearch-analysis-dynamic-synonym-*.zip -d target/extracted

FROM docker.elastic.co/elasticsearch/elasticsearch:8.7.1

COPY --from=build --chown=elasticsearch:elasticsearch /app/target/extracted /usr/share/elasticsearch/plugins/dynamic-synonym/
