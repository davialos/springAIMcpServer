package com.springaimcpservercommon.jfranalyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JfrAnalyzerCliTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return JfrAnalyzerCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void writesBothReportsAndPrintsTheHotSpot(@TempDir Path dir) throws Exception {
        int code = run(TestRecordings.sample().toString(), "-p", TestRecordings.SAMPLE_PACKAGE, "-o", dir.toString(),
                "--name", "r", "--top=10");
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(dir.resolve("r.html")).isRegularFile();
        assertThat(dir.resolve("r.json")).isRegularFile();
        assertThat(dir.resolve("r.xlsx")).isRegularFile();
        assertThat(dir.resolve("r-summary.json")).isRegularFile();
        assertThat(Files.readString(dir.resolve("r.json"))).contains("\n  \"cpu\": {");
        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("Status: ")
                .contains("Hottest line:").contains("SampleWorkload.java:").contains("Wrote ");
    }

    @Test
    void formatSelectsOutputs(@TempDir Path dir) {
        int code = run(TestRecordings.sample().toString(), "--package=" + TestRecordings.SAMPLE_PACKAGE,
                "--output", dir.toString(), "-n", "only", "--format", "json", "--compact-json");
        assertThat(code).isZero();
        assertThat(dir.resolve("only.json")).isRegularFile();
        assertThat(dir.resolve("only.html")).doesNotExist();
    }

    @Test
    void excelAndSummaryOnly(@TempDir Path dir) {
        int code = run(TestRecordings.sample().toString(), "-p", TestRecordings.SAMPLE_PACKAGE, "-o", dir.toString(),
                "-n", "lead", "--format", "xlsx,summary");
        assertThat(code).isZero();
        assertThat(dir.resolve("lead.xlsx")).isRegularFile();
        assertThat(dir.resolve("lead-summary.json")).isRegularFile();
        assertThat(dir.resolve("lead.html")).doesNotExist();
        assertThat(dir.resolve("lead.json")).doesNotExist();
    }

    @Test
    void badUsageExitsWithTwo() {
        assertThat(run()).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("no recording given").contains("Usage:");
        assertThat(run("missing.jfr")).isEqualTo(2);
        assertThat(run(TestRecordings.sample().toString(), "--bogus")).isEqualTo(2);
        assertThat(run(TestRecordings.sample().toString(), "--top", "x")).isEqualTo(2);
        assertThat(run(TestRecordings.sample().toString(), "--compact-json=yes")).isEqualTo(2);
        assertThat(run(TestRecordings.sample().toString(), "--format", "pdf")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown: pdf");
    }

    @Test
    void helpExitsWithZero() {
        assertThat(run("--help")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Usage: jfr-analyze");
    }

    @Test
    void notARecordingExitsWithOne(@TempDir Path dir) throws Exception {
        Path junk = Files.writeString(dir.resolve("junk.jfr"), "not a recording");
        assertThat(run(junk.toString(), "-o", dir.toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("cannot analyze");
    }
}
