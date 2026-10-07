#!/usr/bin/env bash
# Runs the JavaScript unit tests of the chat UI (<saimcp-chat>, docs/integration/chat-ui-guide.md) with Node's built-in
# test runner. Needs Node 20+ on PATH; installs nothing (the component has no npm dependencies).
#
# Usage: scripts/chat-ui-test.sh [node --test args]
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
module="$root/spring-ai-mcp-server-common-chat-ui"
if ! command -v node >/dev/null 2>&1; then
  echo "node not found: install Node 20+ to run the chat UI tests" >&2
  exit 1
fi
major="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$major" -lt 20 ]; then
  echo "Node $major found; the chat UI tests need Node 20+" >&2
  exit 1
fi
cd "$module"
exec node --test "$@" src/test/js/*.test.mjs
