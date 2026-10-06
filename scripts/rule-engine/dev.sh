#!/usr/bin/env bash
# One command for the complete local rule-engine ecosystem (docker/rule-engine, ADR-0026):
# PostgreSQL, auth service, rule-engine service, console (nginx), seed, Prometheus, Loki, Promtail, Grafana.
#
#   scripts/rule-engine/dev.sh up        generate .env on first run, build, start, seed, wait until everything answers
#   scripts/rule-engine/dev.sh down      stop (data volumes are kept)
#   scripts/rule-engine/dev.sh reset     stop and DELETE all data volumes (next `up` starts from a clean database)
#   scripts/rule-engine/dev.sh ps | logs [service] | seed | smoke | dashboards | e2e | e2e-angular | config | urls
#
# Needs: Docker with the compose plugin, bash, curl, python3. Nothing else is installed on the host.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dir="$root/docker/rule-engine"
env_file="$dir/.env"
dc() { docker compose --project-directory "$dir" -f "$dir/docker-compose.yml" --env-file "$env_file" "$@"; }

rand() { python3 -c 'import secrets,sys; print(secrets.token_urlsafe(int(sys.argv[1])))' "$1"; }

ensure_env() {
  if [ -f "$env_file" ]; then return; fi
  echo "creating $env_file with random secrets (git-ignored)"
  umask 077
  sed -e "s|^JWT_SECRET=.*|JWT_SECRET=$(rand 48)|" \
      -e "s|^POSTGRES_SUPERUSER_PASSWORD=.*|POSTGRES_SUPERUSER_PASSWORD=$(rand 18)|" \
      -e "s|^RULES_APP_PASSWORD=.*|RULES_APP_PASSWORD=$(rand 18)|" \
      -e "s|^RULES_MIGRATOR_PASSWORD=.*|RULES_MIGRATOR_PASSWORD=$(rand 18)|" \
      -e "s|^AUTH_DB_PASSWORD=.*|AUTH_DB_PASSWORD=$(rand 18)|" \
      -e "s|^GRAFANA_RO_PASSWORD=.*|GRAFANA_RO_PASSWORD=$(rand 18)|" \
      "$dir/.env.example" > "$env_file"
}

# value of a variable from .env, else the default
val() { local v; v="$(grep -E "^$1=" "$env_file" 2>/dev/null | tail -1 | cut -d= -f2-)"; echo "${v:-$2}"; }

ports() {
  UI="$(val RE_UI_PORT 8080)"; UING="$(val RE_UI_NG_PORT 8081)"; AUTH="$(val RE_AUTH_PORT 8091)"; RULES="$(val RE_RULES_PORT 8092)"
  GRAFANA="$(val RE_GRAFANA_PORT 3000)"; PROM="$(val RE_PROMETHEUS_PORT 9090)"; LOKI="$(val RE_LOKI_PORT 3100)"; PG="$(val RE_PG_PORT 55433)"
}

urls() {
  ports
  cat <<EOT

  Console      http://localhost:$UI        admin / $(val SEED_ADMIN_PASSWORD admin123)   (ADMIN: rules, groups, logs)
                                          user  / $(val SEED_USER_PASSWORD user123)   (USER: rules and groups of own organization)
  Angular UI   http://localhost:$UING        same logins, plus the AI assistant (offline templates unless ANTHROPIC_API_KEY is set in .env)
  Grafana      http://localhost:$GRAFANA        $(val GRAFANA_ADMIN_USER admin) / $(val GRAFANA_ADMIN_PASSWORD admin)   dashboard "Rule engine — operations"
  Prometheus   http://localhost:$PROM        Loki http://localhost:$LOKI        PostgreSQL localhost:$PG (user postgres, password in $env_file)
  Auth API     http://localhost:$AUTH        Rule-engine API http://localhost:$RULES   (the console reaches both through :$UI)

EOT
}

# poll `$1` (a command) until it succeeds, up to $2 seconds; prints dots
wait_for() {
  local what="$1" secs="$2"; shift 2
  local end=$((SECONDS + secs))
  printf '  waiting for %s ' "$what"
  until "$@" >/dev/null 2>&1; do
    if [ $SECONDS -ge $end ]; then echo " TIMEOUT"; return 1; fi
    printf '.'; sleep 2
  done
  echo " ok"
}

seed_done() { [ "$(dc ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}' seed 2>/dev/null | head -1)" = "seed exited 0" ]; }

cmd_up() {
  ensure_env; ports
  dc up -d --build
  echo "waiting for the stack:"
  wait_for "rule-engine service" 240 curl -fsS "http://127.0.0.1:$RULES/actuator/health/readiness"
  wait_for "auth service" 120 curl -fsS "http://127.0.0.1:$AUTH/actuator/health/readiness"
  wait_for "seed" 180 seed_done || { dc logs seed | tail -20; exit 1; }
  wait_for "console" 60 curl -fsS "http://127.0.0.1:$UI/healthz"
  wait_for "Angular console" 60 curl -fsS "http://127.0.0.1:$UING/healthz"
  wait_for "Grafana" 90 curl -fsS "http://127.0.0.1:$GRAFANA/api/health"
  urls
}

grafana_get() {  # grafana_get <path>
  curl -fsS -u "$(val GRAFANA_ADMIN_USER admin):$(val GRAFANA_ADMIN_PASSWORD admin)" "http://127.0.0.1:$GRAFANA$1"
}

cmd_smoke() {
  ensure_env; ports
  local fail=0
  ok()  { printf '  ok    %s\n' "$1"; }
  bad() { printf '  FAIL  %s\n' "$1"; fail=1; }
  check() { local what="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$what"; else bad "$what"; fi; }
  local admin_pw user_pw; admin_pw="$(val SEED_ADMIN_PASSWORD admin123)"; user_pw="$(val SEED_USER_PASSWORD user123)"

  echo "services:"
  check "console health"                curl -fsS "http://127.0.0.1:$UI/healthz"
  check "Angular console health"        curl -fsS "http://127.0.0.1:$UING/healthz"
  check "auth readiness"                curl -fsS "http://127.0.0.1:$AUTH/actuator/health/readiness"
  check "rule-engine readiness"         curl -fsS "http://127.0.0.1:$RULES/actuator/health/readiness"

  echo "login (Protocol Buffers on the wire):"
  # LoginRequest{username=1, password=2} hand-encoded: tag, length, bytes (credentials < 128 bytes)
  proto_login() { printf "\\x0a\\x$(printf %02x ${#1})%s\\x12\\x$(printf %02x ${#2})%s" "$1" "$2"; }
  hdr="$(proto_login admin "$admin_pw" | curl -sS -D - -o /dev/null -X POST -H 'Content-Type: application/x-protobuf' \
         --data-binary @- "http://127.0.0.1:$UI/auth/login")"
  echo "$hdr" | head -1 | grep -q ' 200' && ok "POST /auth/login -> 200" || bad "POST /auth/login -> 200"
  echo "$hdr" | grep -qi '^content-type: application/x-protobuf' && ok "reply is application/x-protobuf" || bad "reply is application/x-protobuf"
  echo "$hdr" | grep -qi '^cache-control:.*no-store' && ok "reply is no-store" || bad "reply is no-store"
  proto_login smoke-nobody wrong-password | curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/x-protobuf' \
      --data-binary @- "http://127.0.0.1:$UI/auth/login" | grep -q 401 && ok "unknown user / wrong password -> 401" || bad "unknown user / wrong password -> 401"

  echo "authorization through the console origin:"
  token_of() {
    curl -fsS -X POST -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}" "http://127.0.0.1:$UI/auth/login" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])'
  }
  local at ut; at="$(token_of admin "$admin_pw")"; ut="$(token_of user "$user_pw")"
  code() { curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $2" "http://127.0.0.1:$UI$1"; }
  [ "$(code /api/v1/admin/logs/summary "$at")" = 200 ] && ok "admin reads logs" || bad "admin reads logs"
  [ "$(code /api/v1/admin/logs/summary "$ut")" = 403 ] && ok "user is refused the logs (403)" || bad "user is refused the logs (403)"
  [ "$(code /api/v1/admin/logs/summary "")" = 401 ] && ok "no token is refused (401)" || bad "no token is refused (401)"
  check "rule groups are listed for the user" curl -fsS -H "Authorization: Bearer $ut" "http://127.0.0.1:$UI/api/v1/rule-groups"

  echo "AI assistant (Angular console origin):"
  assistant_json="$(curl -fsS -H "Authorization: Bearer $at" "http://127.0.0.1:$UING/api/v1/assistant")"
  slug="$(echo "$assistant_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("agentSlug") or "")')"
  [ -n "$slug" ] && ok "assistant provisioned for the tenant ($slug)" || bad "assistant provisioned for the tenant"
  turn="$(curl -sN -m 40 -X POST -H "Authorization: Bearer $at" -H 'Content-Type: application/json' -d '{"message":"list the active rules"}' \
          "http://127.0.0.1:$UING/dynamic-ai/api/agents/$slug/chat/stream" || true)"
  echo "$turn" | grep -q 'event:text.delta' && echo "$turn" | grep -q 'event:turn.end' && ok "chat streams text and ends the turn" || bad "chat streams text and ends the turn"
  [ "$(code /api/v1/assistant "")" = 401 ] && ok "assistant info needs a token" || bad "assistant info needs a token"
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$UING/dynamic-ai/admin/api/v1/me")" = 404 ] && ok "the library's admin API is not reachable from the browser origin" || bad "the library's admin API is not reachable from the browser origin"

  echo "observability:"
  check "Prometheus targets up"   bash -c "curl -fsS 'http://127.0.0.1:$PROM/api/v1/targets' | python3 -c 'import json,sys; t=json.load(sys.stdin)[\"data\"][\"activeTargets\"]; sys.exit(0 if t and all(x[\"health\"]==\"up\" for x in t) else 1)'"
  # Loki reports ready ~15 s after it starts, and promtail needs a moment to ship the first lines: retry for up to a minute
  retry() { local n=30; while [ $n -gt 0 ]; do "$@" >/dev/null 2>&1 && return 0; n=$((n - 1)); sleep 2; done; return 1; }
  check "Loki ready"              retry curl -fsS "http://127.0.0.1:$LOKI/ready"
  loki_has_logs() { curl -fsSG "http://127.0.0.1:$LOKI/loki/api/v1/query" --data-urlencode 'query=count_over_time({service="rule-engine-service"}[1h])' | grep -q '"result":\[{'; }
  check "Loki holds service logs" retry loki_has_logs
  for ds in prometheus loki rules-db; do
    check "Grafana datasource $ds healthy" bash -c "$(declare -f grafana_get val); env_file='$env_file' GRAFANA=$GRAFANA; grafana_get /api/datasources/uid/$ds/health | grep -q '\"status\":\"OK\"'"
  done
  check "Grafana dashboard provisioned" bash -c "$(declare -f grafana_get val); env_file='$env_file' GRAFANA=$GRAFANA; grafana_get /api/dashboards/uid/rule-engine-ops"

  if [ $fail -eq 0 ]; then echo "smoke: all checks passed"; else echo "smoke: FAILED"; return 1; fi
}

cmd_e2e_angular() {
  ensure_env; ports
  (cd "$dir/ui-angular" && npm ci --no-audit --no-fund && npx playwright install chromium && \
     E2E_BASE_URL="http://127.0.0.1:$UING" E2E_ADMIN_PASSWORD="$(val SEED_ADMIN_PASSWORD admin123)" \
     E2E_USER_PASSWORD="$(val SEED_USER_PASSWORD user123)" npx playwright test)
}

cmd_e2e() {
  ensure_env; ports
  # the browser suite runs on the host against the running stack's console
  (cd "$dir/ui" && npm ci --no-audit --no-fund && npx playwright install chromium && \
     E2E_BASE_URL="http://127.0.0.1:$UI" E2E_ADMIN_PASSWORD="$(val SEED_ADMIN_PASSWORD admin123)" \
     E2E_USER_PASSWORD="$(val SEED_USER_PASSWORD user123)" npx playwright test)
}

case "${1:-up}" in
  up)     cmd_up ;;
  down)   ensure_env; dc down ;;
  reset)  ensure_env; dc down -v --remove-orphans ;;
  ps)     ensure_env; dc ps -a ;;
  logs)   ensure_env; shift; dc logs -f --tail=100 "$@" ;;
  seed)   ensure_env; dc run --rm seed ;;
  smoke)  cmd_smoke ;;
  e2e)    cmd_e2e ;;
  e2e-angular) cmd_e2e_angular ;;
  dashboards) ensure_env; ports; GRAFANA_URL="http://127.0.0.1:$GRAFANA" GRAFANA_USER="$(val GRAFANA_ADMIN_USER admin)" \
            GRAFANA_PASSWORD="$(val GRAFANA_ADMIN_PASSWORD admin)" python3 "$root/scripts/rule-engine/check-dashboard.py" ;;
  config) ensure_env; dc config ;;
  urls)   ensure_env; urls ;;
  *)      sed -n 2,11p "${BASH_SOURCE[0]}"; exit 2 ;;
esac
