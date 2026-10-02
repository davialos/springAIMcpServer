#!/usr/bin/env bash
# Runs the load-test generator CLI (module spring-ai-mcp-server-common-loadtest, ADR-0022, LLD-16).
#
#   scripts/loadtest.sh discover --project ../my-service
#   scripts/loadtest.sh generate --project ../my-service --openapi http://localhost:8080/v3/api-docs --harvest
#   scripts/loadtest.sh run --suite ../my-service/load-tests --mode mixed-spike --data-mode mixed
#
# Compiles the module on first use (offline, from ./offline-repo) and runs it with JDK 25.
# JDBC drivers: PostgreSQL is bundled; MySQL, MariaDB, SQL Server, Oracle, H2, SQLite, DB2 are picked up from the
# local Maven (~/.m2) or Gradle cache, where building the Spring project put them. Others or overrides:
#   LOADTEST_CLASSPATH=/path/driver.jar scripts/loadtest.sh …   (LOADTEST_DRIVER_SEARCH=0 turns the search off)
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
# drivers the target Spring project already uses (Maven layout: group/artifact; Gradle: group.dots/artifact)
if [ "${LOADTEST_DRIVER_SEARCH:-1}" != "0" ]; then
  m2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
  gradle="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
  for ga in com.mysql:mysql-connector-j mysql:mysql-connector-java org.mariadb.jdbc:mariadb-java-client \
            com.microsoft.sqlserver:mssql-jdbc com.oracle.database.jdbc:ojdbc11 com.oracle.database.jdbc:ojdbc8 \
            com.h2database:h2 org.xerial:sqlite-jdbc com.ibm.db2:jcc; do
    g="${ga%%:*}"; a="${ga##*:}"
    j="$( { find "$m2/${g//.//}/$a" "$gradle/$g/$a" -name "$a-*.jar" ! -name '*-sources.jar' \
              ! -name '*-javadoc.jar' 2>/dev/null || true; } | sort -V | tail -1)"
    [ -n "$j" ] && cp="$cp:$j"
  done
fi
exec java -cp "$cp${LOADTEST_CLASSPATH:+:$LOADTEST_CLASSPATH}" \
  com.springaimcpservercommon.loadtest.cli.LoadTestCli "$@"
