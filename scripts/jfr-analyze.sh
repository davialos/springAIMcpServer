#!/usr/bin/env bash
# Analyzes a Java Flight Recorder file and writes an HTML and a JSON report (docs/tools/jfr-analyzer.md).
#
# Usage: scripts/jfr-analyze.sh <recording.jfr> -p com.yourcompany [-p ...] [-o dir] [--help]
#
# Builds the analyzer on first use (offline, from ./offline-repo). Needs JDK 25 on PATH or in JAVA_HOME.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="0.1.0-SNAPSHOT"
analyzer="$root/spring-ai-mcp-server-common-jfr-analyzer/target/spring-ai-mcp-server-common-jfr-analyzer-$version.jar"
core="$root/spring-ai-mcp-server-common-core/target/spring-ai-mcp-server-common-core-$version.jar"
if [ ! -f "$analyzer" ] || [ ! -f "$core" ]; then
  echo "Building the analyzer (once)..." >&2
  "$root/scripts/build-offline.sh" -q package -DskipTests \
    -pl spring-ai-mcp-server-common-jfr-analyzer -am >&2
fi
java_cmd="java"
if [ -n "${JAVA_HOME:-}" ]; then java_cmd="$JAVA_HOME/bin/java"; fi
# core.json.CanonicalJson needs only the JDK at run time, so the two jars are the whole class path.
exec "$java_cmd" -cp "$analyzer:$core" com.springaimcpservercommon.jfranalyzer.JfrAnalyzerCli "$@"
