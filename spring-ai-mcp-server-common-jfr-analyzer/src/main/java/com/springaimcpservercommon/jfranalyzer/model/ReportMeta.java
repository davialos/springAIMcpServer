package com.springaimcpservercommon.jfranalyzer.model;

import java.util.List;
import java.util.Map;

/**
 * What was analyzed and how.
 *
 * @param schemaVersion    version of this report's JSON shape, {@value #SCHEMA_VERSION}
 * @param recordingFile    path of the {@code .jfr} file
 * @param fileSizeBytes    its size
 * @param analyzedAt       ISO-8601 UTC time of the analysis
 * @param packages         requested package prefixes (empty = every frame counts)
 * @param excludedPackages package prefixes never attributed to (e.g. generated proxies)
 * @param recordingStart   ISO-8601 time of the first event
 * @param recordingEnd     ISO-8601 time of the last event
 * @param durationMillis   recording span
 * @param jvm              recorded JVM
 * @param eventCounts      number of events per JFR event type
 */
public record ReportMeta(String schemaVersion, String recordingFile, long fileSizeBytes, String analyzedAt,
                         List<String> packages, List<String> excludedPackages, String recordingStart,
                         String recordingEnd, long durationMillis, JvmInfo jvm, Map<String, Long> eventCounts) {

    /** Current JSON shape; bumped when a field is renamed or removed (additions keep it). */
    public static final String SCHEMA_VERSION = "jfr-analyzer/report/2";
}
