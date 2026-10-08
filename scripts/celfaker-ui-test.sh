#!/usr/bin/env bash
# Runs the JavaScript tests of the celfaker flow designer and of the generated k6 runtime (Node 20+, no npm packages).
# Usage: scripts/celfaker-ui-test.sh [node --test args]
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v node >/dev/null 2>&1 || { echo "node not found: install Node 20+" >&2; exit 1; }
cd "$root/spring-ai-mcp-server-common-celfaker"
exec node --test "$@" src/test/js/*.test.mjs
