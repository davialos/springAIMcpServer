#!/usr/bin/env bash
# Starts a throw-away local PostgreSQL (initdb under /tmp), applies every dynamic_ai migration (V1..Vn) and the
# rule-engine sample data. No Docker needed; requires PostgreSQL 15+ binaries (default: /usr/lib/postgresql/16).
#
#   scripts/rule-engine/local-db.sh start    # init + start + migrate + sample data  (port 54329, db dai, user dai)
#   scripts/rule-engine/local-db.sh psql     # open psql
#   scripts/rule-engine/local-db.sh stop     # stop and delete
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PGBIN="${PGBIN:-/usr/lib/postgresql/16/bin}"
PGDIR="${PGDIR:-/tmp/dai-rule-engine-pg}"
PGPORT="${PGPORT:-54329}"
mig="$root/spring-ai-mcp-server-common-persistence/src/main/resources/db/dynamic-ai/migration"
as_pg() { if [ "$(id -u)" = 0 ]; then su postgres -s /bin/bash -c "$*"; else bash -c "$*"; fi; }
sql() { PGPASSWORD=dai "$PGBIN/psql" -h localhost -p "$PGPORT" -U dai -d dai -v ON_ERROR_STOP=1 -q "$@"; }
case "${1:-start}" in
  start)
    # a previous instance must be stopped before its data directory is replaced
    if [ -d "$PGDIR/data" ]; then as_pg "$PGBIN/pg_ctl -D $PGDIR/data -m immediate stop" >/dev/null 2>&1 || true; fi
    rm -rf "$PGDIR"; mkdir -p "$PGDIR"; if [ "$(id -u)" = 0 ]; then chown postgres "$PGDIR"; fi
    as_pg "$PGBIN/initdb -D $PGDIR/data -A trust -U postgres >/dev/null"
    as_pg "$PGBIN/pg_ctl -D $PGDIR/data -o '-p $PGPORT -k $PGDIR' -l $PGDIR/log -w start >/dev/null"
    "$PGBIN/psql" -h "$PGDIR" -p "$PGPORT" -U postgres -q -c "CREATE ROLE dai LOGIN PASSWORD 'dai' SUPERUSER" -c "CREATE DATABASE dai OWNER dai" -c "CREATE DATABASE dai_host_it OWNER dai"
    sql -c "CREATE SCHEMA dynamic_ai"
    # numeric order (V2 before V10), search_path = dynamic_ai exactly as Flyway sets it
    for f in $(ls "$mig" | sort -t_ -k1.2 -n); do
      PGOPTIONS="-c search_path=dynamic_ai" sql -f "$mig/$f"
    done
    PGOPTIONS="-c search_path=dynamic_ai" sql -f "$root/scripts/rule-engine/sample-data.sql"
    echo "ready: jdbc:postgresql://localhost:$PGPORT/dai  user=dai password=dai  schema=dynamic_ai"
    echo "empty database for host-application ITs (they migrate it themselves): jdbc:postgresql://localhost:$PGPORT/dai_host_it" ;;
  psql) PGOPTIONS="-c search_path=dynamic_ai" sql ;;
  stop) as_pg "$PGBIN/pg_ctl -D $PGDIR/data -m immediate stop" || true; rm -rf "$PGDIR" ;;
esac
