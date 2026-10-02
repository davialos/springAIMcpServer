#!/usr/bin/env bash
# Universal performance test: k6 load (scripts/loadtest.sh) against any running HTTP service, with a Java Flight
# Recording taken from the service's JVM during the load and analyzed afterwards (scripts/jfr-analyze.sh).
# Guide: docs/tools/perf-test.md. Example configuration: scripts/perf-test.env.example.
#
#   scripts/perf-test.sh --target http://orders.internal:8080 --mode mixed-load -p com.acme.orders
#   scripts/perf-test.sh --env-file perf/orders-staging.env                    # everything from a config file
#   scripts/perf-test.sh --env-file perf/orders.env --mode stress --vus 50      # flags override the file
#
# Pipeline: config → target health → API discovery + k6 suite generation (data from the database, user files,
# recordings) → optional warm-up → JFR start on the target JVM (local, ssh, docker or kubectl) → k6 run →
# JFR stop + fetch → JFR analysis → results folder with summary.md.
# Exit code: k6's (0 ok, 99 thresholds failed, …); 1 when a step failed; 2 on a usage error.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOADTEST="${PERF_LOADTEST_SCRIPT:-$root/scripts/loadtest.sh}"
JFR_ANALYZE="${PERF_JFR_ANALYZE_SCRIPT:-$root/scripts/jfr-analyze.sh}"

usage() {
  cat <<'EOF'
Usage: scripts/perf-test.sh [--env-file FILE] --target URL [options] [-- extra k6 args]

Every option can also be set in the env file (or the environment) as PERF_<NAME>, e.g. --db-url → PERF_DB_URL,
--package → PERF_PACKAGES (comma-separated). Flags win over the env file, the env file over the environment.
Secrets come only from the environment / env file: AUTH_TOKEN, AUTH_USER, AUTH_PASSWORD, API_KEY,
LOADTEST_DB_PASSWORD (or SPRING_DATASOURCE_PASSWORD).

Target
  --env-file FILE          bash KEY=VALUE file (sourced) with any PERF_* setting and secrets; repeatable
  --target URL             base URL of the service, incl. context path (required), e.g. http://srv:8080/shop
  --name NAME              service name for the results folder (default: host-port of --target)
  --out DIR                results root (default ./perf-results); run folder = DIR/NAME/<time>-<mode>
  --health-path PATH       wait until GET <target>PATH answers 2xx/3xx, e.g. /actuator/health
  --health-timeout SEC     how long to wait (default 120)
  --header 'Name: value'   header for discovery fetches and harvesting (repeatable)

API discovery (default: <target>/v3/api-docs, else <target>/actuator/mappings)
  --openapi URL|FILE       OpenAPI 3 document          --openapi-path PATH  probe path (default /v3/api-docs)
  --project DIR            Spring Boot project sources --actuator URL|FILE  /actuator/mappings
  --har FILE               browser recording (repeatable; enables journey-<profile> modes)
  --include PATTERN        keep matching APIs (repeatable)   --exclude PATTERN  drop matching APIs (repeatable)
  --suite DIR              k6 suite dir (default DIR/NAME/suite; team edits are kept across runs)
  --skip-generate          reuse the existing suite as is

Data source
  --data-mode MODE         auto | dummy | random | real | user | mixed (default auto)
  --db-url JDBC            database with real values (default: SPRING_DATASOURCE_URL when set)
  --db-user USER           (default SPRING_DATASOURCE_USERNAME); password: LOADTEST_DB_PASSWORD env
  --db-schema SCHEMA       --no-db  never connect      --sample-size N  values per column (200)
  --user-data FILE         JSON/YAML/CSV user values (repeatable)
  --value key=v1,v2        user values for one field (repeatable)
  --bind key=table.column  force a field to real values of a column (repeatable)
  --harvest                also collect real ids from the running API's list endpoints
  --drop-unverified        drop user ids that are not in the database
  --auth TYPE              none | bearer | basic | apiKey | login   --login-path PATH

Load (k6)
  --mode MODE              smoke | load | stress | spike | soak | breakpoint, mixed-<p>, journey-<p> (default smoke)
  --api ID[,ID]            only these APIs      --vus N   --rate N   --duration-scale F   --per-api parallel
  --read-only              GET/HEAD only        --warmup MODE  k6 run before recording starts (JIT warm-up)
  --k6 PATH                k6 binary (default $K6_BIN or k6)   --allow-prod  pass ALLOW_PROD=true to the suite
  --skip-load              record only (for --jfr-duration seconds), no k6

Profiling (JFR, target JVM on JDK 11+)
  --no-jfr                 do not record
  --jvm-pid PID            JVM to record                     --jvm-match REGEX  pick it from `jcmd -l`
                           (default: the only JVM, or the one listening on the --target port when local)
  --ssh USER@HOST          JVM runs on another server (ssh/scp)   --ssh-opts 'OPTS'  e.g. '-i key -p 2222'
  --docker CONTAINER       JVM runs in a container (docker exec/cp)
  --kube-pod POD           JVM runs in a pod (kubectl exec/cp)  --kube-namespace NS  --kube-container NAME
  --jcmd 'CMD'             jcmd command where the JVM runs (default jcmd), e.g. 'sudo -u orders jcmd'
  --jfr-settings NAME      default | profile | path to a .jfc on the JVM side (default profile)
  --jfr-exceptions         also record exception throw sites (JDK 17+)
  --jfr-remote-dir DIR     where the JVM writes the recording (default /tmp)
  --jfr-max-duration D     safety cap, the JVM ends the recording itself (default 3h)
  --jfr-duration SEC       recording length with --skip-load (default 120)
  -p, --package PREFIX     your code's packages for the analysis (repeatable); without it all frames count
  -x, --exclude-package P  never attribute to this package (repeatable)

Other
  --dry-run                print every step's command; nothing runs, nothing is written    -h, --help
EOF
}

die() { echo "perf-test: $*" >&2; exit 2; }
log() { printf '[perf-test %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
warn() { printf '[perf-test %s] WARNING: %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
is_true() { case "${1:-}" in true|TRUE|True|yes|1|on) return 0 ;; *) return 1 ;; esac; }
split_csv() { # prints one item per line; empty input → nothing
  local IFS=','; local x
  for x in ${1:-}; do x="${x#"${x%%[![:space:]]*}"}"; x="${x%"${x##*[![:space:]]}"}"; [ -n "$x" ] && printf '%s\n' "$x"; done
  return 0
}

# ── 1. configuration: env files first, then flags ───────────────────────────────────────────────────────
args=("$@"); env_files=()
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[i]}" in
    --) break ;;
    --env-file) [ $((i + 1)) -lt ${#args[@]} ] || die "--env-file needs a value"; env_files+=("${args[i + 1]}") ;;
    --env-file=*) env_files+=("${args[i]#--env-file=}") ;;
  esac
done
for f in "${env_files[@]:-}"; do
  [ -z "$f" ] && continue
  [ -f "$f" ] || die "env file not found: $f"
  set -a
  # shellcheck disable=SC1090
  source "$f"
  set +a
done

# List settings: an env file may give a bash array or a comma-separated string; flags append.
headers=();    if declare -p PERF_HEADERS >/dev/null 2>&1; then headers=("${PERF_HEADERS[@]}"); fi
values=();     if declare -p PERF_VALUES >/dev/null 2>&1; then values=("${PERF_VALUES[@]}"); fi
binds=();      if declare -p PERF_BINDS >/dev/null 2>&1; then binds=("${PERF_BINDS[@]}"); fi
k6_args=();    if declare -p PERF_K6_ARGS >/dev/null 2>&1; then k6_args=("${PERF_K6_ARGS[@]}"); fi
mapfile -t hars < <(split_csv "${PERF_HAR:-}")
mapfile -t includes < <(split_csv "${PERF_INCLUDE:-}")
mapfile -t excludes < <(split_csv "${PERF_EXCLUDE:-}")
mapfile -t user_data < <(split_csv "${PERF_USER_DATA:-}")
mapfile -t packages < <(split_csv "${PERF_PACKAGES:-}")
mapfile -t exclude_packages < <(split_csv "${PERF_EXCLUDE_PACKAGES:-}")

set -- "${args[@]}"
need() { [ $# -ge 2 ] || die "$1 needs a value"; }
while [ $# -gt 0 ]; do
  opt="$1"; val=""
  if [[ "$opt" == --*=* ]]; then val="${opt#*=}"; opt="${opt%%=*}"; set -- "$opt" "$val" "${@:2}"; fi
  case "$opt" in
    -h|--help) usage; exit 0 ;;
    --) shift; k6_args+=("$@"); break ;;
    --env-file) need "$@"; shift ;;
    # flags
    --skip-generate) PERF_SKIP_GENERATE=true ;;
    --skip-load) PERF_SKIP_LOAD=true ;;
    --no-db) PERF_NO_DB=true ;;
    --harvest) PERF_HARVEST=true ;;
    --drop-unverified) PERF_DROP_UNVERIFIED=true ;;
    --read-only) PERF_READ_ONLY=true ;;
    --allow-prod) PERF_ALLOW_PROD=true ;;
    --no-jfr) PERF_JFR=false ;;
    --jfr-exceptions) PERF_JFR_EXCEPTIONS=true ;;
    --dry-run) PERF_DRY_RUN=true ;;
    # repeatable
    --header) need "$@"; headers+=("$2"); shift ;;
    --har) need "$@"; hars+=("$2"); shift ;;
    --include) need "$@"; includes+=("$2"); shift ;;
    --exclude) need "$@"; excludes+=("$2"); shift ;;
    --user-data) need "$@"; user_data+=("$2"); shift ;;
    --value) need "$@"; values+=("$2"); shift ;;
    --bind) need "$@"; binds+=("$2"); shift ;;
    -p|--package) need "$@"; mapfile -t -O "${#packages[@]}" packages < <(split_csv "$2"); shift ;;
    -x|--exclude-package) need "$@"; mapfile -t -O "${#exclude_packages[@]}" exclude_packages < <(split_csv "$2"); shift ;;
    # single values: --foo-bar → PERF_FOO_BAR
    --target|--name|--out|--health-path|--health-timeout|--openapi|--openapi-path|--project|--actuator|--suite|\
    --data-mode|--db-url|--db-user|--db-schema|--sample-size|--auth|--login-path|--mode|--api|--vus|--rate|\
    --duration-scale|--per-api|--warmup|--k6|--jvm-pid|--jvm-match|--ssh|--ssh-opts|--docker|--kube-pod|\
    --kube-namespace|--kube-container|--jcmd|--jfr-settings|--jfr-remote-dir|--jfr-max-duration|--jfr-duration)
      need "$@"
      var="PERF_$(printf '%s' "${opt#--}" | tr 'a-z-' 'A-Z_')"
      printf -v "$var" '%s' "$2"
      shift ;;
    *) usage >&2; die "unknown option: $opt" ;;
  esac
  shift
done

# Defaults (and Spring's datasource variables, so a service's own env file can be reused as the data source)
TARGET="${PERF_TARGET:-}"
[ -n "$TARGET" ] || { usage >&2; die "--target (or PERF_TARGET) is required"; }
[[ "$TARGET" =~ ^https?:// ]] || die "--target must be an http(s) URL: $TARGET"
TARGET="${TARGET%/}"
MODE="${PERF_MODE:-smoke}"
DRY="${PERF_DRY_RUN:-false}"
DB_URL="${PERF_DB_URL:-${SPRING_DATASOURCE_URL:-}}"
DB_USER="${PERF_DB_USER:-${SPRING_DATASOURCE_USERNAME:-}}"
if [ -z "${LOADTEST_DB_PASSWORD:-}" ] && [ -n "${SPRING_DATASOURCE_PASSWORD:-}" ]; then
  export LOADTEST_DB_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
fi
JFR_ON=true; is_true "${PERF_JFR:-true}" || JFR_ON=false
if is_true "${PERF_SKIP_LOAD:-}" && [ "$JFR_ON" = false ]; then die "--skip-load with --no-jfr leaves nothing to do"; fi

hostport="${TARGET#*://}"; hostport="${hostport%%/*}"; hostport="${hostport##*@}"
target_host="${hostport%:*}"; target_port="${hostport##*:}"
if [ "$target_port" = "$hostport" ] || [[ "$hostport" == \[*\] ]]; then
  case "$TARGET" in https://*) target_port=443 ;; *) target_port=80 ;; esac
fi
target_host="${target_host#[}"; target_host="${target_host%]}"
NAME="${PERF_NAME:-${target_host}-${target_port}}"
NAME="$(printf '%s' "$NAME" | tr -c 'A-Za-z0-9._-' '-')"
OUT="${PERF_OUT:-$PWD/perf-results}"
SUITE="${PERF_SUITE:-$OUT/$NAME/suite}"
stamp="$(date +%Y%m%d-%H%M%S)"
label="$MODE"; is_true "${PERF_SKIP_LOAD:-}" && label="jfr-only"
RUN_DIR="$OUT/$NAME/$stamp-$(printf '%s' "$label" | tr -c 'A-Za-z0-9._-' '-')"
run_id="perf-$stamp-$$"

abspath() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$PWD" "${1#./}" ;; esac; }
RUN_DIR="$(abspath "$RUN_DIR")"
SUITE="$(abspath "$SUITE")"

quote() { local s="" a; for a in "$@"; do s+="$(printf '%q' "$a") "; done; printf '%s' "${s% }"; }
mask() { # hides passwords in URLs and the values of credential headers in logged commands
  sed -E 's/(password=)[^&; ]*/\1***/Ig; s/((authorization|cookie|api-key|token|secret)\\?:)(\\ |[^ ])*/\1***/Ig'
}
step() { # logs and runs a command (or only logs it with --dry-run)
  log "\$ $(quote "$@" | mask)"
  is_true "$DRY" && return 0
  "$@"
}

# ── where the JVM lives: local | ssh | docker | kubectl ─────────────────────────────────────────────────
WHERE=local
[ -n "${PERF_SSH:-}" ] && WHERE=ssh
[ -n "${PERF_DOCKER:-}" ] && { [ "$WHERE" = local ] || die "use only one of --ssh, --docker, --kube-pod"; WHERE=docker; }
[ -n "${PERF_KUBE_POD:-}" ] && { [ "$WHERE" = local ] || die "use only one of --ssh, --docker, --kube-pod"; WHERE=kubectl; }
read -r -a ssh_opts <<< "${PERF_SSH_OPTS:-}"
read -r -a jcmd_cmd <<< "${PERF_JCMD:-jcmd}"
kube=(kubectl); [ -n "${PERF_KUBE_NAMESPACE:-}" ] && kube+=(-n "$PERF_KUBE_NAMESPACE")
kube_c=(); [ -n "${PERF_KUBE_CONTAINER:-}" ] && kube_c=(-c "$PERF_KUBE_CONTAINER")

on_jvm_host() { # runs a command where the JVM lives
  case "$WHERE" in
    local) "$@" ;;
    ssh) ssh "${ssh_opts[@]}" "$PERF_SSH" "$(quote "$@")" ;;
    docker) docker exec "$PERF_DOCKER" "$@" ;;
    kubectl) "${kube[@]}" exec "$PERF_KUBE_POD" "${kube_c[@]}" -- "$@" ;;
  esac
}
fetch_from_jvm_host() { # remote-path local-path
  case "$WHERE" in
    local) cp "$1" "$2" ;;
    ssh)
      local port_opts=() i
      for ((i = 0; i < ${#ssh_opts[@]}; i++)); do # scp spells ssh's -p as -P
        if [ "${ssh_opts[i]}" = "-p" ]; then port_opts+=(-P); else port_opts+=("${ssh_opts[i]}"); fi
      done
      scp -q "${port_opts[@]}" "$PERF_SSH:$1" "$2" ;;
    docker) docker cp "$PERF_DOCKER:$1" "$2" ;;
    kubectl) "${kube[@]}" cp "${kube_c[@]}" "$PERF_KUBE_POD:$1" "$2" ;;
  esac
}
jcmd_on() { on_jvm_host "${jcmd_cmd[@]}" "$@"; }

is_local_target() {
  case "$target_host" in localhost|127.*|::1|0.0.0.0) return 0 ;; esac
  [ "$target_host" = "$(hostname 2>/dev/null)" ] || [ "$target_host" = "$(hostname -f 2>/dev/null)" ]
}

resolve_jvm_pid() {
  if [ -n "${PERF_JVM_PID:-}" ]; then printf '%s' "$PERF_JVM_PID"; return; fi
  local listing candidates
  listing="$(jcmd_on -l 2>/dev/null)" || { warn "cannot list JVMs with '${jcmd_cmd[*]} -l' ($WHERE)"; return 1; }
  candidates="$(printf '%s\n' "$listing" | grep -Ev '^[0-9]+ +(sun\.tools\.jcmd\.JCmd|jdk\.jcmd/sun\.tools\.jcmd\.JCmd)' \
    | grep -E '^[0-9]+' || true)"
  if [ -n "${PERF_JVM_MATCH:-}" ]; then
    candidates="$(printf '%s\n' "$candidates" | grep -E -- "$PERF_JVM_MATCH" || true)"
  fi
  local n; n="$(printf '%s' "$candidates" | grep -c . || true)"
  if [ "$n" -gt 1 ] && [ "$WHERE" = local ] && is_local_target && command -v ss >/dev/null 2>&1; then
    local owner
    owner="$(ss -Hltnp "sport = :$target_port" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2 || true)"
    if [ -n "$owner" ] && printf '%s\n' "$candidates" | grep -qE "^$owner( |$)"; then
      candidates="$(printf '%s\n' "$candidates" | grep -E "^$owner( |$)")"; n=1
    fi
  fi
  if [ "$n" -eq 1 ]; then printf '%s' "${candidates%% *}"; return; fi
  if [ "$n" -eq 0 ]; then
    warn "no JVM found${PERF_JVM_MATCH:+ matching '$PERF_JVM_MATCH'} ($WHERE). jcmd lists only JVMs of the same user: try --jcmd 'sudo -u <service-user> jcmd'"
  else
    warn "several JVMs match, choose one with --jvm-pid or --jvm-match:"; printf '%s\n' "$candidates" >&2
  fi
  return 1
}

# ── recording lifecycle; the trap guarantees the target is never left recording ─────────────────────────
JVM_PID=""; REC_ACTIVE=false
JFR_REMOTE="${PERF_JFR_REMOTE_DIR:-/tmp}/$run_id.jfr"
JFR_LOCAL="$RUN_DIR/recording.jfr"
jfr_start() {
  local opts=(JFR.start "name=$run_id" "settings=${PERF_JFR_SETTINGS:-profile}" "filename=$JFR_REMOTE"
              "duration=${PERF_JFR_MAX_DURATION:-3h}" disk=true)
  is_true "${PERF_JFR_EXCEPTIONS:-}" && opts+=("jdk.JavaExceptionThrow#enabled=true")
  log "JFR: starting on pid $JVM_PID ($WHERE) → $JFR_REMOTE"
  if is_true "$DRY"; then log "\$ ${jcmd_cmd[*]} $JVM_PID ${opts[*]}"; return 0; fi
  local outp
  outp="$(jcmd_on "$JVM_PID" "${opts[@]}" 2>&1)" || true
  printf '%s\n' "$outp" >> "$RUN_DIR/jfr-jcmd.log"
  if printf '%s' "$outp" | grep -q "Started recording"; then
    REC_ACTIVE=true
  else
    warn "JFR.start failed: $(printf '%s' "$outp" | tail -3 | tr '\n' ' ')"; return 1
  fi
}
jfr_stop() {
  [ "$REC_ACTIVE" = true ] || return 0
  REC_ACTIVE=false
  log "JFR: stopping and writing the recording"
  local outp
  outp="$(jcmd_on "$JVM_PID" JFR.stop "name=$run_id" 2>&1)" || true
  printf '%s\n' "$outp" >> "$RUN_DIR/jfr-jcmd.log"
  if ! printf '%s' "$outp" | grep -qiE "stopped recording|written to|^$JVM_PID:"; then
    warn "JFR.stop: $(printf '%s' "$outp" | tail -2 | tr '\n' ' ')"
  fi
}
cleanup() {
  local code=$?
  if [ "$REC_ACTIVE" = true ]; then
    warn "interrupted: stopping the recording on the target"
    jfr_stop || true
    if fetch_from_jvm_host "$JFR_REMOTE" "$JFR_LOCAL" 2>/dev/null; then
      on_jvm_host rm -f "$JFR_REMOTE" 2>/dev/null || true
      warn "partial recording saved: $JFR_LOCAL (analyze: $JFR_ANALYZE $JFR_LOCAL -p <package>)"
    else
      warn "the recording is left at $WHERE:$JFR_REMOTE"
    fi
  fi
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ── 2. preflight ────────────────────────────────────────────────────────────────────────────────────────
command -v java >/dev/null 2>&1 || die "java (JDK 25) is needed on PATH for the generator and the analyzer"
k6_bin="${PERF_K6:-${K6_BIN:-k6}}"
if ! is_true "${PERF_SKIP_LOAD:-}" && ! is_true "$DRY" && ! command -v "$k6_bin" >/dev/null 2>&1; then
  die "k6 not found ('$k6_bin'): install it (https://grafana.com/docs/k6/latest/set-up/install-k6/) or pass --k6 PATH"
fi
if [ "$JFR_ON" = true ]; then
  case "$WHERE" in ssh) tool=ssh ;; docker) tool=docker ;; kubectl) tool=kubectl ;; *) tool="${jcmd_cmd[0]}" ;; esac
  if ! command -v "$tool" >/dev/null 2>&1; then
    if is_true "$DRY"; then warn "$tool not found (needed to reach the JVM)"
    else die "$tool not found (needed to reach the JVM; or pass --no-jfr)"; fi
  fi
fi
curl_headers=()
for h in "${headers[@]:-}"; do [ -n "$h" ] && curl_headers+=(-H "$h"); done
if [ -n "${AUTH_TOKEN:-}" ]; then curl_headers+=(-H "Authorization: Bearer $AUTH_TOKEN"); fi

if [ -n "${PERF_HEALTH_PATH:-}" ] && ! is_true "$DRY"; then
  command -v curl >/dev/null 2>&1 || die "curl is needed for --health-path"
  deadline=$(( $(date +%s) + ${PERF_HEALTH_TIMEOUT:-120} ))
  log "waiting for $TARGET$PERF_HEALTH_PATH"
  until code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "${curl_headers[@]}" "$TARGET$PERF_HEALTH_PATH" || true)"; \
        [[ "$code" =~ ^[23] ]]; do
    [ "$(date +%s)" -lt "$deadline" ] || { echo "perf-test: $TARGET$PERF_HEALTH_PATH not healthy (last HTTP $code)" >&2; exit 1; }
    sleep 3
  done
  log "target healthy (HTTP $code)"
fi

# ── record the run's parameters (no secrets) ────────────────────────────────────────────────────────────
is_true "$DRY" || mkdir -p "$RUN_DIR" "$SUITE"
is_true "$DRY" || {
  echo "# perf-test run $stamp"
  echo "target=$TARGET"; echo "name=$NAME"; echo "mode=$MODE"; echo "data_mode=${PERF_DATA_MODE:-auto}"
  echo "suite=$SUITE"; echo "db_url=$(printf '%s' "$DB_URL" | mask)"; echo "db_user=$DB_USER"
  echo "jfr=$JFR_ON"; echo "jvm_location=$WHERE${PERF_SSH:+ $PERF_SSH}${PERF_DOCKER:+ $PERF_DOCKER}${PERF_KUBE_POD:+ $PERF_KUBE_POD}"
  echo "packages=$(IFS=,; echo "${packages[*]:-}")"
  echo "vus=${PERF_VUS:-}"; echo "rate=${PERF_RATE:-}"; echo "duration_scale=${PERF_DURATION_SCALE:-}"; echo "api=${PERF_API:-}"
} > "$RUN_DIR/run.env"
if is_true "$DRY"; then log "run folder: $RUN_DIR (dry run: not created)"; else log "run folder: $RUN_DIR"; fi

# ── 3. discovery + suite generation ─────────────────────────────────────────────────────────────────────
if is_true "${PERF_SKIP_LOAD:-}"; then
  log "--skip-load: no suite needed"
elif ! is_true "${PERF_SKIP_GENERATE:-}" || [ ! -f "$SUITE/main.js" ]; then
  is_true "${PERF_SKIP_GENERATE:-}" && warn "--skip-generate: no suite in $SUITE yet, generating it"
  gen=(generate --out "$SUITE" --base-url "$TARGET" --data-mode "${PERF_DATA_MODE:-auto}")
  sources=0
  if [ -n "${PERF_OPENAPI:-}" ]; then gen+=(--openapi "$PERF_OPENAPI"); sources=$((sources + 1)); fi
  if [ -n "${PERF_PROJECT:-}" ]; then gen+=(--project "$PERF_PROJECT"); sources=$((sources + 1)); fi
  if [ -n "${PERF_ACTUATOR:-}" ]; then gen+=(--actuator "$PERF_ACTUATOR"); sources=$((sources + 1)); fi
  for h in "${hars[@]:-}"; do [ -n "$h" ] && { gen+=(--har "$h"); sources=$((sources + 1)); }; done
  if [ "$sources" -eq 0 ]; then # probe the running service and keep a snapshot of what was used
    command -v curl >/dev/null 2>&1 || die "no discovery source given and curl is missing: pass --openapi/--project/--actuator/--har"
    probe() { curl -fsS -m 20 "${curl_headers[@]}" "$1" -o "$2" 2>/dev/null && [ -s "$2" ]; }
    if is_true "$DRY"; then
      gen+=(--openapi "$TARGET${PERF_OPENAPI_PATH:-/v3/api-docs}")
    elif probe "$TARGET${PERF_OPENAPI_PATH:-/v3/api-docs}" "$RUN_DIR/openapi.json"; then
      log "discovery: OpenAPI at $TARGET${PERF_OPENAPI_PATH:-/v3/api-docs}"; gen+=(--openapi "$RUN_DIR/openapi.json")
    elif probe "$TARGET/actuator/mappings" "$RUN_DIR/actuator-mappings.json"; then
      log "discovery: /actuator/mappings"; gen+=(--actuator "$RUN_DIR/actuator-mappings.json")
    else
      rm -f "$RUN_DIR/openapi.json" "$RUN_DIR/actuator-mappings.json"
      echo "perf-test: no API description at $TARGET${PERF_OPENAPI_PATH:-/v3/api-docs} or $TARGET/actuator/mappings;" \
           "pass --openapi, --project, --actuator or --har" >&2
      exit 1
    fi
  fi
  for x in "${includes[@]:-}"; do [ -n "$x" ] && gen+=(--include "$x"); done
  for x in "${excludes[@]:-}"; do [ -n "$x" ] && gen+=(--exclude "$x"); done
  for x in "${headers[@]:-}"; do [ -n "$x" ] && gen+=(--header "$x"); done
  if is_true "${PERF_NO_DB:-}"; then gen+=(--no-db)
  elif [ -n "$DB_URL" ]; then
    gen+=(--db-url "$DB_URL")
    [ -n "$DB_USER" ] && gen+=(--db-user "$DB_USER")
    [ -n "${PERF_DB_SCHEMA:-}" ] && gen+=(--db-schema "$PERF_DB_SCHEMA")
  fi
  [ -n "${PERF_SAMPLE_SIZE:-}" ] && gen+=(--sample-size "$PERF_SAMPLE_SIZE")
  for x in "${user_data[@]:-}"; do [ -n "$x" ] && gen+=(--user-data "$x"); done
  for x in "${values[@]:-}"; do [ -n "$x" ] && gen+=(--value "$x"); done
  for x in "${binds[@]:-}"; do [ -n "$x" ] && gen+=(--bind "$x"); done
  is_true "${PERF_HARVEST:-}" && gen+=(--harvest)
  is_true "${PERF_DROP_UNVERIFIED:-}" && gen+=(--drop-unverified)
  [ -n "${PERF_AUTH:-}" ] && gen+=(--auth "$PERF_AUTH")
  [ -n "${PERF_LOGIN_PATH:-}" ] && gen+=(--login-path "$PERF_LOGIN_PATH")
  log "generating the k6 suite in $SUITE"
  if is_true "$DRY"; then step "$LOADTEST" "${gen[@]}"
  else
    log "\$ $(quote "$LOADTEST" "${gen[@]}" | mask)"
    "$LOADTEST" "${gen[@]}" > >(tee "$RUN_DIR/generate.log") 2>&1 \
      || { echo "perf-test: suite generation failed, see $RUN_DIR/generate.log" >&2; exit 1; }
  fi
else
  log "reusing the suite in $SUITE"
fi

# ── 4. load + recording ─────────────────────────────────────────────────────────────────────────────────
k6_run() { # mode → k6 exit code
  local r=(run --suite "$SUITE" --mode "$1" --base-url "$TARGET" --k6 "$k6_bin")
  [ -n "${PERF_DATA_MODE:-}" ] && r+=(--data-mode "$PERF_DATA_MODE")
  [ -n "${PERF_API:-}" ] && r+=(--api "$PERF_API")
  [ -n "${PERF_VUS:-}" ] && r+=(--vus "$PERF_VUS")
  [ -n "${PERF_RATE:-}" ] && r+=(--rate "$PERF_RATE")
  [ -n "${PERF_DURATION_SCALE:-}" ] && r+=(--duration-scale "$PERF_DURATION_SCALE")
  [ -n "${PERF_PER_API:-}" ] && r+=(--per-api "$PERF_PER_API")
  is_true "${PERF_READ_ONLY:-}" && r+=(--read-only)
  [ ${#k6_args[@]} -gt 0 ] && r+=(-- "${k6_args[@]}")
  if is_true "$DRY"; then step "$LOADTEST" "${r[@]}"; return 0; fi
  log "\$ $(quote "$LOADTEST" "${r[@]}" | mask)"
  local code=0
  "$LOADTEST" "${r[@]}" > >(tee -a "$RUN_DIR/k6.log") 2>&1 || code=$?
  return "$code"
}
is_true "${PERF_ALLOW_PROD:-}" && export ALLOW_PROD=true

if [ -n "${PERF_WARMUP:-}" ] && ! is_true "${PERF_SKIP_LOAD:-}"; then
  log "warm-up: $PERF_WARMUP (not recorded)"
  k6_run "$PERF_WARMUP" || warn "warm-up ended with exit code $? (continuing)"
fi

jfr_ok=false
if [ "$JFR_ON" = true ]; then
  if is_true "$DRY"; then JVM_PID="${PERF_JVM_PID:-<pid>}"; jfr_start; jfr_ok=true
  elif JVM_PID="$(resolve_jvm_pid)"; then
    log "JFR: target JVM pid $JVM_PID"
    jcmd_on "$JVM_PID" VM.version > "$RUN_DIR/jvm-version.txt" 2>&1 || true
    jfr_start && jfr_ok=true
  fi
  [ "$jfr_ok" = true ] || warn "continuing without a recording"
fi

marker="$RUN_DIR/.k6-start"; is_true "$DRY" || touch "$marker"
k6_code=0
if is_true "${PERF_SKIP_LOAD:-}"; then
  log "recording for ${PERF_JFR_DURATION:-120}s without load (--skip-load)"
  is_true "$DRY" || sleep "${PERF_JFR_DURATION:-120}"
else
  log "load: mode $MODE against $TARGET"
  k6_run "$MODE" || k6_code=$?
  is_true "$DRY" || log "k6 finished with exit code $k6_code"
fi

# ── 5. stop, fetch and analyze the recording ────────────────────────────────────────────────────────────
analysis_code=""
if [ "$jfr_ok" = true ] && ! is_true "$DRY"; then
  jfr_stop
  if fetch_from_jvm_host "$JFR_REMOTE" "$JFR_LOCAL" 2>>"$RUN_DIR/jfr-jcmd.log"; then
    on_jvm_host rm -f "$JFR_REMOTE" 2>/dev/null || true
    log "JFR: $(du -h "$JFR_LOCAL" | cut -f1) recording in $JFR_LOCAL"
    an=("$JFR_LOCAL" -o "$RUN_DIR/jfr" -n jfr-report)
    for p in "${packages[@]:-}"; do [ -n "$p" ] && an+=(-p "$p"); done
    for p in "${exclude_packages[@]:-}"; do [ -n "$p" ] && an+=(-x "$p"); done
    [ ${#packages[@]} -eq 0 ] && warn "no --package given: every frame counts as your code in the JFR report"
    log "\$ $(quote "$JFR_ANALYZE" "${an[@]}")"
    analysis_code=0
    "$JFR_ANALYZE" "${an[@]}" > >(tee "$RUN_DIR/jfr-analyze.log") 2>&1 || analysis_code=$?
  else
    warn "could not copy $WHERE:$JFR_REMOTE (see $RUN_DIR/jfr-jcmd.log); the recording stays there"
    analysis_code=1
  fi
elif [ "$jfr_ok" = true ]; then
  log "\$ ${jcmd_cmd[*]} $JVM_PID JFR.stop name=$run_id; fetch $JFR_REMOTE; $JFR_ANALYZE $JFR_LOCAL -o $RUN_DIR/jfr"
fi

if is_true "$DRY"; then exit 0; fi

# k6 reports written by the suite's handleSummary during this run
mkdir -p "$RUN_DIR/k6"
if [ -d "$SUITE/reports" ]; then
  find "$SUITE/reports" -maxdepth 1 -type f -newer "$marker" -exec cp {} "$RUN_DIR/k6/" \;
fi
rm -f "$marker"

# ── 6. summary ──────────────────────────────────────────────────────────────────────────────────────────
k6_md="$(find "$RUN_DIR/k6" -name '*.md' 2>/dev/null | sort | tail -1)"
{
  echo "# Performance run: $NAME ($stamp)"
  echo
  echo "| | |"; echo "|---|---|"
  echo "| Target | $TARGET |"
  if is_true "${PERF_SKIP_LOAD:-}"; then echo "| Mode | recording only, ${PERF_JFR_DURATION:-120} s |"
  else echo "| Mode / data | $MODE / ${PERF_DATA_MODE:-auto} |"; fi
  echo "| Data source | $( [ -n "$DB_URL" ] && ! is_true "${PERF_NO_DB:-}" && printf '%s' "$DB_URL" | mask || echo "none (dummy/user values)")$( [ ${#user_data[@]} -gt 0 ] && printf ' + %s' "${user_data[*]}") |"
  if is_true "${PERF_SKIP_LOAD:-}"; then echo "| k6 | skipped |"; else echo "| k6 exit code | $k6_code$( [ "$k6_code" = 99 ] && echo ' (thresholds failed)') |"; fi
  if [ "$JFR_ON" = false ]; then echo "| JFR | off |"
  elif [ "$jfr_ok" = true ]; then echo "| JFR | pid $JVM_PID ($WHERE), analysis exit code ${analysis_code:-n/a} |"
  else echo "| JFR | not recorded (see log) |"; fi
  echo "| Suite | $SUITE |"
  echo
  echo "## Files"
  [ -n "$k6_md" ] && echo "- k6 report: k6/$(basename "$k6_md")"
  [ -f "$RUN_DIR/k6.log" ] && echo "- k6 console: k6.log"
  [ -f "$RUN_DIR/jfr/jfr-report.html" ] && echo "- JFR report: jfr/jfr-report.html (and .json)"
  [ -f "$JFR_LOCAL" ] && echo "- recording: recording.jfr"
  if [ -n "$k6_md" ]; then echo; echo "## k6"; echo; cat "$k6_md"; fi
  if [ -f "$RUN_DIR/jfr-analyze.log" ]; then echo; echo "## JFR findings"; echo; echo '```'; cat "$RUN_DIR/jfr-analyze.log"; echo '```'; fi
} > "$RUN_DIR/summary.md"
log "summary: $RUN_DIR/summary.md"

if [ "$k6_code" -ne 0 ]; then exit "$k6_code"; fi
if [ -n "$analysis_code" ] && [ "$analysis_code" -ne 0 ]; then exit 1; fi
if [ "$JFR_ON" = true ] && [ "$jfr_ok" = false ] && ! is_true "$DRY"; then exit 1; fi
exit 0
