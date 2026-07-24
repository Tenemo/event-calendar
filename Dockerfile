FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS maven-toolchain

FROM eclipse-temurin:26.0.1_8-jdk@sha256:939e35776c4582f5454276c42a9ca3825df1b4a983ed2edd4cd9b4e130bb0eeb AS build

COPY --from=maven-toolchain \
    /usr/share/maven \
    /root/.m2/wrapper/dists/apache-maven-3.9.11/apache-maven-3.9.11

WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw \
    && ./mvnw -B -ntp -Dmaven.test.skip=true -DincludeScope=compile dependency:go-offline

ARG RAILWAY_GIT_COMMIT_SHA=""

COPY src/main src/main
RUN revision_resource="src/main/resources/META-INF/deployment-revision" \
    && rm -f "$revision_resource" \
    && if [ -n "$RAILWAY_GIT_COMMIT_SHA" ]; then \
        if [ "$(printf '%s' "$RAILWAY_GIT_COMMIT_SHA" | wc -c)" -ne 40 ] \
            || ! printf '%s' "$RAILWAY_GIT_COMMIT_SHA" | grep -Eq '^[0-9a-fA-F]{40}$'; then \
            echo "RAILWAY_GIT_COMMIT_SHA must be a full 40-character hexadecimal commit SHA." >&2; \
            exit 1; \
        fi; \
        printf '%s' "$RAILWAY_GIT_COMMIT_SHA" | tr 'A-F' 'a-f' > "$revision_resource"; \
    fi \
    && ./mvnw -B -ntp -o -Dmaven.test.skip=true package

FROM icr.io/appcafe/open-liberty:kernel-slim-java25-openj9-ubi-minimal@sha256:b923eaba0d4489d98ef484e2386a0fbe8bf3a40124451a611d42dced393ce1cd

USER 1001

ENV OPENJ9_SCC=false

COPY --chown=1001:0 src/main/liberty/config/server.xml /config/
COPY --chown=1001:0 src/main/liberty/container/bootstrap.properties /config/

RUN features.sh

COPY --chown=1001:0 --from=build \
    /app/.build/package/liberty-resources/postgresql/postgresql.jar \
    /opt/ol/wlp/usr/shared/resources/postgresql/postgresql.jar
COPY --chown=1001:0 --from=build \
    /app/.build/package/shared-calendar.war \
    /config/apps/shared-calendar.war

ENV GENERATE_LTPA_KEYS_PASSWORD=false
RUN configure.sh
