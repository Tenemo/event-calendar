FROM maven:3.9.15-eclipse-temurin-26@sha256:029a8e2838ae68238ffb8be407cddbb3f07d4d839c60c6f26c619a69fd184531 AS maven-toolchain

FROM eclipse-temurin:25.0.3_9-jdk@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea AS build

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
