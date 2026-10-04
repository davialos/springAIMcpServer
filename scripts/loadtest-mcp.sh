#!/usr/bin/env bash
# Starts the load-test MCP server on stdio (module spring-ai-mcp-server-common-loadtest-mcp, ADR-0022, LLD-16).
#
#   claude mcp add spring-loadtest -- /path/to/springAIMcpServer/scripts/loadtest-mcp.sh --root /path/to/workspace
#
# Tools: loadtest_discover, loadtest_generate, loadtest_run, loadtest_report, loadtest_compare, loadtest_modes.
# Every path a tool receives must lie inside --root (default: the directory the client starts the server in).
# Compiles the modules on first use (offline, from ./offline-repo) and runs with JDK 25 (JAVA_HOME or PATH).
# Standard output is the MCP protocol: everything else goes to standard error.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="${LOADTEST_M2:-$root/offline-repo}"
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"

stale() { # $1 module: no classes yet, or sources newer than them
  [ ! -d "$root/$1/target/classes/com" ] || \
    [ -n "$(find "$root/$1/src/main" -newer "$root/$1/target/classes" -type f -print -quit 2>/dev/null)" ]
}
if stale spring-ai-mcp-server-common-loadtest || stale spring-ai-mcp-server-common-loadtest-mcp; then
  mvn -q -B --offline "-Dmaven.repo.local=$repo" -f "$root/pom.xml" \
      -pl spring-ai-mcp-server-common-loadtest,spring-ai-mcp-server-common-loadtest-mcp compile >&2
  touch "$root/spring-ai-mcp-server-common-loadtest/target/classes" \
        "$root/spring-ai-mcp-server-common-loadtest-mcp/target/classes"
fi

jar() { # newest jar of a groupId/artifactId path in the repository
  find "$repo/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort -V | tail -1
}
cp="$root/spring-ai-mcp-server-common-loadtest-mcp/target/classes:$root/spring-ai-mcp-server-common-loadtest/target/classes"
for a in tools/jackson/core/jackson-databind tools/jackson/core/jackson-core \
         com/fasterxml/jackson/core/jackson-annotations tools/jackson/dataformat/jackson-dataformat-yaml \
         org/snakeyaml/snakeyaml-engine org/postgresql/postgresql org/jspecify/jspecify \
         io/modelcontextprotocol/sdk/mcp-core io/modelcontextprotocol/sdk/mcp-json-jackson3 \
         io/projectreactor/reactor-core org/reactivestreams/reactive-streams org/slf4j/slf4j-api \
         com/networknt/json-schema-validator com/ethlo/time/itu; do
  j="$(jar "$a")"
  if [ -z "$j" ]; then echo "loadtest-mcp: $a not found in $repo" >&2; exit 1; fi
  cp="$cp:$j"
done
exec "$java" -cp "$cp${LOADTEST_CLASSPATH:+:$LOADTEST_CLASSPATH}" \
  com.springaimcpservercommon.loadtest.mcp.LoadTestMcpServer "$@"
