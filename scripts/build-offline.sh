#!/usr/bin/env bash
# Builds the project with no network access, resolving every dependency and Maven plugin from the vendored
# repository in ./offline-repo (see docs/offline-build.md).
#
# Usage: scripts/build-offline.sh [maven args]     goal defaults to verify (unit + integration tests)
# Integration tests need a Docker daemon (Testcontainers); add -DskipITs to run unit tests only.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
has_goal=false
for arg in "$@"; do
  case "$arg" in -*) ;; *) has_goal=true ;; esac
done
if [ "$has_goal" = false ]; then set -- "$@" verify; fi
exec mvn -B --offline "-Dmaven.repo.local=$root/offline-repo" "$@"
