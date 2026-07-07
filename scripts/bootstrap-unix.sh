#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

"${SCRIPT_DIR}/check-toolchain.sh"
"${SCRIPT_DIR}/prepare-liberty-dev.sh"
