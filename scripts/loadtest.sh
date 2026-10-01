#!/usr/bin/env bash
# Runs the load-test generator CLI (module spring-ai-mcp-server-common-loadtest, ADR-0022, LLD-16).
#
#   scripts/loadtest.sh discover --project ../my-service
#   scripts/loadtest.sh generate --project ../my-service --openapi http://localhost:8080/v3/api-docs --harvest
#   scripts/loadtest.sh run --suite ../my-service/load-tests --mode mixed-spike --data-mode mixed
#
# Compiles the module on first use (offline, from ./offline-repo) and runs it with JDK 25.
# Extra JDBC drivers (MySQL, SQL Server, Oracle …): LOADTEST_CLASSPATH=/path/driver.jar scripts/loadtest.sh …
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
module="$root/spring-ai-mcp-server-common-loadtest"
repo="${LOADTEST_M2:-$root/offline-repo}"

if [ ! -d "$module/target/classes/com" ] || \
   [ -n "$(find "$module/src/main" -newer "$module/target/classes" -type f -print -quit 2>/dev/null)" ]; then
  mvn -q -B --offline "-Dmaven.repo.local=$repo" -f "$module/pom.xml" compile >&2
  touch "$module/target/classes"
fi

jar() { # newest jar of a groupId/artifactId path in the repository
  find "$repo/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort -V | tail -1
}
cp="$module/target/classes"
for a in tools/jackson/core/jackson-databind tools/jackson/core/jackson-core \
         com/fasterxml/jackson/core/jackson-annotations tools/jackson/dataformat/jackson-dataformat-yaml \
         org/snakeyaml/snakeyaml-engine org/postgresql/postgresql org/jspecify/jspecify; do
  j="$(jar "$a")"
  if [ -z "$j" ]; then echo "loadtest: $a not found in $repo" >&2; exit 1; fi
  cp="$cp:$j"
done
exec java -cp "$cp${LOADTEST_CLASSPATH:+:$LOADTEST_CLASSPATH}" \
  com.springaimcpservercommon.loadtest.cli.LoadTestCli "$@"
