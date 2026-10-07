#!/bin/sh
# One-shot after the services are healthy (so their Flyway migrations have run): grants for Grafana, the sample loan rules (only into
# an EMPTY rule set, so a team's own rules are never touched) and the dev history. Safe to run again: `scripts/rule-engine/dev.sh seed`.
set -eu
q() { psql -v ON_ERROR_STOP=1 -q "$@"; }
dynamic() { PGOPTIONS="-c search_path=dynamic_ai" psql -v ON_ERROR_STOP=1 -q "$@"; }

q -f /seed/grants.sql

if [ "$(psql -Atq -c 'SELECT count(*) FROM dynamic_ai.dai_re_module')" = "0" ]; then
  dynamic -f /scripts/sample-data.sql
  echo "seed: sample loan rules loaded"
else
  echo "seed: rules already present, sample data not loaded"
fi

dynamic -f /seed/dev-extra.sql
echo "seed: done"
