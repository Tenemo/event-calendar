FROM eclipse-temurin:25-jdk AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw \
    && ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM icr.io/appcafe/open-liberty:kernel-slim-java25-openj9-ubi-minimal

USER 0
RUN microdnf install -y curl-minimal \
    && microdnf clean all
USER 1001

ENV OPENJ9_SCC=false

COPY --chown=1001:0 src/main/liberty/config/server.xml /config/
COPY --chown=1001:0 src/main/liberty/container/bootstrap.properties /config/

RUN features.sh

COPY --chown=1001:0 --from=build \
    /app/target/liberty/wlp/usr/shared/resources/postgresql/postgresql.jar \
    /opt/ol/wlp/usr/shared/resources/postgresql/postgresql.jar
COPY --chown=1001:0 --from=build \
    /app/target/shared-calendar.war \
    /config/apps/shared-calendar.war

RUN configure.sh
