FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build

WORKDIR /app

COPY pom.xml ./
RUN mvn -B -ntp -Dmaven.test.skip=true dependency:go-offline dependency:resolve

COPY src/main src/main
RUN mvn -B -ntp -o -Dmaven.test.skip=true package

FROM icr.io/appcafe/open-liberty:kernel-slim-java25-openj9-ubi-minimal@sha256:b923eaba0d4489d98ef484e2386a0fbe8bf3a40124451a611d42dced393ce1cd

USER 1001

ENV HTTP_HOST=*
ENV OPENJ9_SCC=false

COPY --chown=1001:0 src/main/liberty/config/server.xml /config/
COPY --chown=1001:0 src/main/liberty/container/bootstrap.properties /config/
RUN features.sh

COPY --chown=1001:0 --from=build /app/.build/package/liberty-resources/postgresql/postgresql.jar /opt/ol/wlp/usr/shared/resources/postgresql/postgresql.jar
COPY --chown=1001:0 --from=build /app/.build/package/shared-calendar.war /config/apps/shared-calendar.war

RUN GENERATE_LTPA_KEYS_PASSWORD=false configure.sh
