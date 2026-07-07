#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)"
POSTGRESQL_VERSION="${POSTGRESQL_VERSION:-42.7.13}"

mkdir -p "${PROJECT_DIR}/src/main/liberty/config/resources"
"${PROJECT_DIR}/mvnw" -q dependency:copy \
  -Dartifact="org.postgresql:postgresql:${POSTGRESQL_VERSION}" \
  -DoutputDirectory="${PROJECT_DIR}/src/main/liberty/config/resources"
