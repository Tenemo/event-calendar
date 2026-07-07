#!/usr/bin/env bash
set -euo pipefail

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command '$1' was not found on PATH." >&2
    exit 1
  fi
}

require_command java
require_command docker
require_command mise

echo "Java:"
java -version

echo "Docker:"
docker --version
docker compose version

echo "mise:"
mise --version
