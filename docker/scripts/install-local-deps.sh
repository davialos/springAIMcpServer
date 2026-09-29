#!/usr/bin/env bash
# install-local-deps.sh
#
# Extracts dist/maven-local-repo.zip into the local Maven repository so this
# developer's Maven cache is seeded and all builds work without downloading
# anything from the internet.
#
# Run this once after cloning the repo (or whenever a teammate ships a new
# bundle after adding/upgrading dependencies).
#
# Usage
#   ./docker/scripts/install-local-deps.sh [--bundle path/to/maven-local-repo.zip]
#
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEFAULT_BUNDLE="${REPO_ROOT}/dist/maven-local-repo.zip"
BUNDLE="${DEFAULT_BUNDLE}"

# Parse --bundle override
while [[ $# -gt 0 ]]; do
  case "$1" in
    --bundle) BUNDLE="$2"; shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

if [ ! -f "${BUNDLE}" ]; then
  echo ""
  echo "✗  Bundle not found: ${BUNDLE}"
  echo ""
  echo "   Generate it first (needs Docker running):"
  echo "     ./docker/scripts/prepare-local-deps.sh"
  echo ""
  exit 1
fi

M2_REPO="${HOME}/.m2/repository"
mkdir -p "${M2_REPO}"

BUNDLE_SIZE=$(du -sh "${BUNDLE}" | cut -f1)
echo ""
echo "→ Extracting ${BUNDLE} (${BUNDLE_SIZE}) into ${M2_REPO} ..."
echo "  (existing artifacts are overwritten; nothing is deleted)"
echo ""

unzip -q -o "${BUNDLE}" -d "${M2_REPO}"

JAR_COUNT=$(find "${M2_REPO}" -name "*.jar" | wc -l | tr -d ' ')
echo "✓ Done. ${JAR_COUNT} JARs now in ${M2_REPO}"
echo ""
echo "Build with the bundled settings:"
echo "  mvn -s docker/maven/settings.xml install -DskipTests"
echo ""
echo "Build fully offline:"
echo "  mvn -s docker/maven/settings.xml --offline install -DskipTests"
echo ""
