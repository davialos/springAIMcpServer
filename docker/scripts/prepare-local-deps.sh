#!/usr/bin/env bash
# prepare-local-deps.sh
#
# Downloads every external Maven dependency for springAIMcpServerCommon into a
# local repository tree, then zips it to dist/maven-local-repo.zip so
# developers can build the project offline or seed a Nexus/Artifactory cache.
#
# Requirements: Docker Desktop running (Mac or Linux).
# No local Java or Maven install needed.
#
# Usage
#   ./docker/scripts/prepare-local-deps.sh            # full download + zip
#   ./docker/scripts/prepare-local-deps.sh --zip-only # skip download, re-zip existing cache
#   ./docker/scripts/prepare-local-deps.sh --no-zip   # download only, skip zip step
#
# Output
#   dist/maven-local-repo.zip   the bundle to share with your team or extract
#                                into ~/.m2/repository
#
# What is included
#   All compile / runtime / test / provided scope JARs, POMs, source JARs and
#   javadoc JARs resolved by every module's dependencies + dependency management.
#   The internal com.springaimcpservercommon SNAPSHOTs are EXCLUDED — they are
#   built from source by mvn install, not fetched from a remote repo.
#
# What is NOT included
#   The internal library modules themselves (built locally), OS-specific
#   binaries, IDE plugins, and any artifact already in ~/.m2/repository that
#   belongs to a groupId that Maven downloads lazily at first use.
# ──────────────────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DIST_DIR="${REPO_ROOT}/dist"
ZIP_FILE="${DIST_DIR}/maven-local-repo.zip"
CACHE_DIR="${DIST_DIR}/.m2-cache"
CONTAINER_NAME="springaimcp-dep-downloader"

# ── Image: JDK 25 + Maven 3.9 ────────────────────────────────────────────────
# eclipse-temurin:25 does not yet bundle Maven; we install it inside the
# container so the version is pinned and independent of the host.
MAVEN_VERSION="3.9.9"
MAVEN_IMAGE="eclipse-temurin:25-jdk-noble"

ZIP_ONLY=false
NO_ZIP=false

for arg in "$@"; do
  case "$arg" in
    --zip-only) ZIP_ONLY=true ;;
    --no-zip)   NO_ZIP=true   ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

mkdir -p "${DIST_DIR}" "${CACHE_DIR}"

# ── Step 1: Download all dependencies in a container ─────────────────────────
if [ "$ZIP_ONLY" = false ]; then
  echo ""
  echo "╔══════════════════════════════════════════════════════════════════╗"
  echo "║  springAIMcpServerCommon — dependency bundle download            ║"
  echo "╚══════════════════════════════════════════════════════════════════╝"
  echo ""
  echo "→ Maven ${MAVEN_VERSION} on ${MAVEN_IMAGE}"
  echo "→ Cache directory: ${CACHE_DIR}"
  echo ""

  docker run --rm \
    --name "${CONTAINER_NAME}" \
    --volume "${REPO_ROOT}:/workspace:ro" \
    --volume "${CACHE_DIR}:/root/.m2/repository" \
    --volume "${REPO_ROOT}/docker/maven/settings.xml:/root/.m2/settings.xml:ro" \
    --workdir /workspace \
    "${MAVEN_IMAGE}" \
    bash -c "
      set -euo pipefail

      # ── Install Maven ──────────────────────────────────────────────────
      echo '==> Installing Maven ${MAVEN_VERSION}...'
      apt-get update -qq && apt-get install -y -qq wget > /dev/null
      wget -q \"https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz\" \
           -O /tmp/maven.tar.gz
      tar -xzf /tmp/maven.tar.gz -C /opt
      export PATH=\"/opt/apache-maven-${MAVEN_VERSION}/bin:\$PATH\"
      mvn --version

      # ── Add Spring repos so Spring Boot 4.x / Spring AI 2.x resolve ──
      # (the settings.xml mounted above already lists these, but we also
      #  need them visible during the dependency:resolve phase)
      SETTINGS_ARGS=\"-s /root/.m2/settings.xml\"

      # ── Step A: resolve all parent/BOM POMs ───────────────────────────
      echo ''
      echo '==> Resolving BOM and parent POMs...'
      mvn \$SETTINGS_ARGS -f /workspace/pom.xml \
          dependency:resolve-plugins \
          --no-transfer-progress -q || true

      # ── Step B: resolve compile + runtime + test deps for all modules ─
      echo ''
      echo '==> Resolving compile/runtime/test dependencies...'
      mvn \$SETTINGS_ARGS -f /workspace/pom.xml \
          dependency:go-offline \
          --no-transfer-progress || true

      # ── Step C: resolve source JARs ───────────────────────────────────
      echo ''
      echo '==> Resolving source JARs...'
      mvn \$SETTINGS_ARGS -f /workspace/pom.xml \
          dependency:resolve -Dclassifier=sources \
          --no-transfer-progress -q || true

      # ── Step D: demo-app deps (Spring AI provider starters) ───────────
      echo ''
      echo '==> Resolving demo-app (provider starter) dependencies...'
      mvn \$SETTINGS_ARGS -f /workspace/docker/demo-app/pom.xml \
          dependency:go-offline \
          --no-transfer-progress -q || true

      mvn \$SETTINGS_ARGS -f /workspace/docker/demo-app/pom.xml \
          dependency:resolve -Dclassifier=sources \
          --no-transfer-progress -q || true

      echo ''
      echo '==> All dependencies resolved.'

      # ── Step E: remove the internal SNAPSHOT modules from the cache ───
      # They must be built from source, not shipped in the bundle.
      echo '==> Pruning internal com.springaimcpservercommon SNAPSHOTs...'
      find /root/.m2/repository/com/springaimcpservercommon \
           -type f -delete 2>/dev/null || true
      find /root/.m2/repository/com/springaimcpservercommon \
           -type d -empty -delete 2>/dev/null || true

      # ── Step F: remove Eclipse/IDE-generated metadata (not needed) ────
      find /root/.m2/repository -name '*.lastUpdated' -delete
      find /root/.m2/repository -name 'resolver-status.properties' -delete
      find /root/.m2/repository -name '_remote.repositories' -delete

      echo '==> Cache pruned and cleaned.'
    "

  echo ""
  echo "✓ Dependencies downloaded to: ${CACHE_DIR}"
  ITEM_COUNT=$(find "${CACHE_DIR}" -name "*.jar" | wc -l | tr -d ' ')
  CACHE_SIZE=$(du -sh "${CACHE_DIR}" | cut -f1)
  echo "  ${ITEM_COUNT} JARs, ${CACHE_SIZE} total"
fi

# ── Step 2: Zip the cache ─────────────────────────────────────────────────────
if [ "$NO_ZIP" = false ]; then
  echo ""
  echo "→ Creating ${ZIP_FILE} ..."
  rm -f "${ZIP_FILE}"
  # Zip from inside the cache dir so paths inside the zip are relative
  # (extract with: unzip -q maven-local-repo.zip -d ~/.m2/repository)
  (cd "${CACHE_DIR}" && zip -r -q "${ZIP_FILE}" .)
  ZIP_SIZE=$(du -sh "${ZIP_FILE}" | cut -f1)
  echo "✓ Bundle created: ${ZIP_FILE} (${ZIP_SIZE})"
fi

echo ""
echo "╔══════════════════════════════════════════════════════════════════╗"
echo "║  Next steps                                                       ║"
echo "╠══════════════════════════════════════════════════════════════════╣"
echo "║  1. Share or copy dist/maven-local-repo.zip to team members.     ║"
echo "║  2. Each developer extracts once:                                 ║"
echo "║       unzip -q dist/maven-local-repo.zip -d ~/.m2/repository     ║"
echo "║  3. Build with the bundled settings:                              ║"
echo "║       mvn -s docker/maven/settings.xml install -DskipTests       ║"
echo "║  4. Build fully offline (no network):                             ║"
echo "║       mvn -s docker/maven/settings.xml --offline install         ║"
echo "╚══════════════════════════════════════════════════════════════════╝"
echo ""
