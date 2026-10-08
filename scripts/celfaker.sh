#!/usr/bin/env bash
# Runs the CEL faker CLI / flow dashboard (module spring-ai-mcp-server-common-celfaker, ADR-0030, LLD-19).
#
#   scripts/celfaker.sh serve                        # drag-and-drop flow dashboard on http://localhost:8099
#   scripts/celfaker.sh example > shop.json          # an example API contract
#   scripts/celfaker.sh analyze --payload order.json --object order
#   scripts/celfaker.sh generate --contract shop.json --out build/celfaker
#   k6 run -e BASE_URL=http://localhost:8080 build/celfaker/k6/main.js
#
# Compiles the module (and the rule engine it builds on) on first use, offline from ./offline-repo, and runs it on JDK 25.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="${CELFAKER_M2:-$root/offline-repo}"
module="$root/spring-ai-mcp-server-common-celfaker"
engine="$root/spring-ai-mcp-server-common-ruleengine"
core="$root/spring-ai-mcp-server-common-core"

stale() { # does $2 hold a source newer than the classes in $1?
  [ ! -d "$1/target/classes/com" ] || [ -n "$(find "$2" -newer "$1/target/classes" -type f -print -quit 2>/dev/null)" ]
}
if stale "$module" "$module/src/main" || stale "$engine" "$engine/src/main" || stale "$core" "$core/src/main"; then
  (cd "$root" && mvn -q -B --offline "-Dmaven.repo.local=$repo" -pl spring-ai-mcp-server-common-celfaker -am \
      -DskipTests -DskipITs compile >&2)
  touch "$module/target/classes" "$engine/target/classes" "$core/target/classes"
fi

jar() { # newest jar of a repository path
  find "$repo/$1" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort -V | tail -1
}
cp="$module/target/classes:$engine/target/classes:$core/target/classes"
for a in dev/cel/cel dev/cel/common dev/cel/compiler dev/cel/protobuf dev/cel/runtime dev/cel/v1alpha1 \
         com/google/protobuf/protobuf-java com/google/guava/guava com/google/guava/failureaccess com/google/re2j/re2j \
         org/antlr/antlr4-runtime org/threeten/threeten-extra org/yaml/snakeyaml org/slf4j/slf4j-api \
         tools/jackson/core/jackson-databind tools/jackson/core/jackson-core com/fasterxml/jackson/core/jackson-annotations \
         org/jspecify/jspecify com/google/auto/value/auto-value-annotations com/google/errorprone/error_prone_annotations; do
  j="$(jar "$a")"
  if [ -z "$j" ]; then echo "celfaker: $a not found in $repo" >&2; exit 1; fi
  cp="$cp:$j"
done
exec java -cp "$cp" com.springaimcpservercommon.celfaker.cli.CelFakerCli "$@"
