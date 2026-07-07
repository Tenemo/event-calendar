#!/usr/bin/env sh
set -eu

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROPERTIES_FILE="${PROJECT_DIR}/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$PROPERTIES_FILE" ]; then
  echo "Missing Maven wrapper properties: $PROPERTIES_FILE" >&2
  exit 1
fi

DISTRIBUTION_URL="$(sed -n 's/^distributionUrl=//p' "$PROPERTIES_FILE" | tail -n 1)"
MAVEN_VERSION="$(printf '%s' "$DISTRIBUTION_URL" | sed -n 's#.*/apache-maven-\([^/]*\)-bin\.zip#\1#p')"

if [ -z "$DISTRIBUTION_URL" ] || [ -z "$MAVEN_VERSION" ]; then
  echo "Could not determine the Maven distribution from $PROPERTIES_FILE" >&2
  exit 1
fi

MAVEN_USER_HOME="${MAVEN_USER_HOME:-${HOME}/.m2}"
MAVEN_BASE="${MAVEN_USER_HOME}/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="${MAVEN_BASE}/apache-maven-${MAVEN_VERSION}"

if command -v mise >/dev/null 2>&1 && [ -f "${PROJECT_DIR}/.mise.toml" ]; then
  MISE_JAVA_HOME="$(mise where java 2>/dev/null || true)"
  if [ -n "$MISE_JAVA_HOME" ] && [ -x "${MISE_JAVA_HOME}/bin/java" ]; then
    export JAVA_HOME="$MISE_JAVA_HOME"
    export PATH="${JAVA_HOME}/bin:${PATH}"
  fi
fi

if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
  mkdir -p "$MAVEN_BASE"
  MAVEN_ZIP="${TMPDIR:-/tmp}/apache-maven-${MAVEN_VERSION}-bin.zip"

  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$DISTRIBUTION_URL" -o "$MAVEN_ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$DISTRIBUTION_URL" -O "$MAVEN_ZIP"
  else
    echo "Install curl or wget so the Maven wrapper can download Maven." >&2
    exit 1
  fi

  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$MAVEN_ZIP" -d "$MAVEN_BASE"
  else
    echo "Install unzip so the Maven wrapper can expand Maven." >&2
    exit 1
  fi
fi

exec "${MAVEN_HOME}/bin/mvn" "$@"
