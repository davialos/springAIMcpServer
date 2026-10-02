package com.springaimcpservercommon.jfranalyzer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Settings of one analysis run.
 *
 * @param recording         the {@code .jfr} file
 * @param packages          package prefixes to attribute costs to; empty means every frame counts
 * @param excludedPackages  package prefixes never attributed to, even inside {@code packages}
 * @param outputDirectory   where the reports are written
 * @param baseName          report file name without extension
 * @param writeHtml         write {@code <baseName>.html}
 * @param writeJson         write {@code <baseName>.json}
 * @param writeExcel        write {@code <baseName>.xlsx}
 * @param writeSummary      write {@code <baseName>-summary.json}
 * @param prettyJson        indent the JSON
 * @param topN              rows per ranked list
 * @param stacksPerHotspot  representative call paths kept per hot spot
 * @param stackDepth        frames kept per call path (leaf first)
 */
public record AnalyzerOptions(Path recording, List<String> packages, List<String> excludedPackages,
                              Path outputDirectory, String baseName, boolean writeHtml, boolean writeJson,
                              boolean writeExcel, boolean writeSummary, boolean prettyJson, int topN,
                              int stacksPerHotspot, int stackDepth) {

    /** Default rows per ranked list. */
    public static final int DEFAULT_TOP_N = 25;
    /** Default representative call paths per hot spot. */
    public static final int DEFAULT_STACKS_PER_HOTSPOT = 5;
    /** Default frames per call path. */
    public static final int DEFAULT_STACK_DEPTH = 24;

    /** Validates and copies the lists. */
    public AnalyzerOptions {
        Objects.requireNonNull(recording, "recording");
        packages = List.copyOf(packages);
        excludedPackages = List.copyOf(excludedPackages);
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        if (baseName.isBlank()) {
            throw new IllegalArgumentException("baseName must not be blank");
        }
        if (topN < 1 || stacksPerHotspot < 0 || stackDepth < 1) {
            throw new IllegalArgumentException("topN and stackDepth must be >= 1, stacksPerHotspot >= 0");
        }
    }

    /**
     * Options with defaults for everything but the recording and packages; reports go next to the recording.
     *
     * @param recording the {@code .jfr} file
     * @param packages  package prefixes
     * @return options
     */
    public static AnalyzerOptions defaults(Path recording, List<String> packages) {
        Path parent = recording.toAbsolutePath().getParent();
        String file = recording.getFileName().toString();
        String base = (file.endsWith(".jfr") ? file.substring(0, file.length() - 4) : file) + "-report";
        return new AnalyzerOptions(recording, packages, List.of(), parent == null ? Path.of(".") : parent, base,
                true, true, true, true, true, DEFAULT_TOP_N, DEFAULT_STACKS_PER_HOTSPOT, DEFAULT_STACK_DEPTH);
    }
}
