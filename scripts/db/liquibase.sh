#!/usr/bin/env bash
# Liquibase deploy wrapper: properties-driven DB config, tag-before-update, auto rollback.
# Usage: scripts/db/liquibase.sh <command> [args]
#   validate            check changelog syntax/checksums (no DB writes)
#   status              list pending changesets
#   migrate             tag -> update; on failure roll back to the tag, exit non-zero
#   rollback <tag>      roll back to a tag (use after a failed application deploy)
#   rollback-count <n>  roll back the last n changesets
#   tag-name            print the pre-deploy tag for the current DEPLOY_ID
#   history             show applied changesets
# Env: APP_ENV (default dev), DEPLOY_ID (default git short sha), DB_* overrides,
#      DB_USERNAME / DB_PASSWORD, LIQUIBASE_IMAGE, LIQUIBASE_CONTEXTS, LIQUIBASE_LABELS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIG="$ROOT/db-migrations"
APP_ENV="${APP_ENV:-dev}"
LIQUIBASE_IMAGE="${LIQUIBASE_IMAGE:-liquibase/liquibase:4.31.1}"
CHANGELOG="changelog/db.changelog-master.yaml"

declare -A P
load_props() {
  local f="$1" k v
  [[ -f "$f" ]] || return 0
  while IFS='=' read -r k v; do
    [[ -z "$k" || "$k" =~ ^[[:space:]]*# ]] && continue
    P["${k// /}"]="${v%$'\r'}"
  done < "$f"
}
load_props "$MIG/environments/defaults.properties"
load_props "$MIG/environments/$APP_ENV.properties"

prop() { # prop db.host -> env override DB_HOST wins
  local env="${1^^}"; env="${env//./_}"
  echo "${!env:-${P[$1]:-}}"
}

build_url() {
  local url type host port name schema
  url="$(prop db.url)"; [[ -n "$url" ]] && { echo "$url"; return; }
  type="$(prop db.type)"; host="$(prop db.host)"; port="$(prop db.port)"; name="$(prop db.name)"
  case "$type" in
    postgresql) echo "jdbc:postgresql://$host:${port:-5432}/$name" ;;
    mysql)      echo "jdbc:mysql://$host:${port:-3306}/$name" ;;
    mariadb)    echo "jdbc:mariadb://$host:${port:-3306}/$name" ;;
    sqlserver)  echo "jdbc:sqlserver://$host:${port:-1433};databaseName=$name;encrypt=true;trustServerCertificate=false" ;;
    oracle)     echo "jdbc:oracle:thin:@//$host:${port:-1521}/$name" ;;
    *) echo "unsupported db.type '$type'" >&2; exit 2 ;;
  esac
}

DEPLOY_ID="${DEPLOY_ID:-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
TAG="pre-deploy-$DEPLOY_ID"

lb() { # lb <liquibase args...> ; password only travels via env, never argv
  local url schema args
  url="$(build_url)"; schema="$(prop db.schema)"
  export LIQUIBASE_COMMAND_USERNAME="${DB_USERNAME:-}" LIQUIBASE_COMMAND_PASSWORD="${DB_PASSWORD:-}"
  args=(--url="$url" --changelog-file="$CHANGELOG")
  [[ -n "$schema" ]] && args+=(--default-schema-name="$schema" --liquibase-schema-name="$schema")
  [[ -n "${LIQUIBASE_CONTEXTS:-}" ]] && args+=(--contexts="$LIQUIBASE_CONTEXTS")
  [[ -n "${LIQUIBASE_LABELS:-}" ]] && args+=(--label-filter="$LIQUIBASE_LABELS")
  if command -v liquibase >/dev/null 2>&1; then
    liquibase --search-path="$MIG" "${args[@]}" "$@"
  else
    docker run --rm --network host \
      -e LIQUIBASE_COMMAND_USERNAME -e LIQUIBASE_COMMAND_PASSWORD \
      -v "$MIG:/liquibase/db-migrations:ro" "$LIQUIBASE_IMAGE" \
      --search-path=/liquibase/db-migrations "${args[@]}" "$@"
  fi
}

cmd="${1:-}"; shift || true
case "$cmd" in
  validate) lb validate ;;
  status)   lb status --verbose ;;
  history)  lb history ;;
  tag-name) echo "$TAG" ;;
  migrate)
    lb validate
    lb tag "$TAG"
    if lb update; then
      echo "migrate: OK (rollback point: $TAG)"
    else
      echo "migrate: FAILED — rolling back to $TAG" >&2
      lb rollback "$TAG" || { echo "ROLLBACK FAILED — manual intervention required" >&2; exit 3; }
      exit 1
    fi ;;
  rollback)
    [[ $# -ge 1 ]] || { echo "usage: rollback <tag>" >&2; exit 2; }
    lb rollback "$1" ;;
  rollback-count)
    [[ $# -ge 1 ]] || { echo "usage: rollback-count <n>" >&2; exit 2; }
    lb rollback-count "$1" ;;
  *) sed -n '2,15p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
