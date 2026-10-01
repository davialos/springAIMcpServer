package com.springaimcpservercommon.jfranalyzer;

import com.springaimcpservercommon.jfranalyzer.sample.SampleWorkload;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;

/** Records {@link SampleWorkload} once per test JVM into a temporary {@code .jfr} file. */
public final class TestRecordings {

    /** Package of the sample workload. */
    public static final String SAMPLE_PACKAGE = "com.springaimcpservercommon.jfranalyzer.sample";

    private static Path recording;

    private TestRecordings() {
    }

    /** @return the recording of the sample workload, made on first use */
    public static synchronized Path sample() {
        if (recording == null) {
            try {
                recording = record();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ParseException e) {
                throw new IllegalStateException(e);
            }
        }
        return recording;
    }

    private static Path record() throws IOException, InterruptedException, ParseException {
        Path dir = Files.createTempDirectory("jfr-analyzer-test");
        Path file = dir.resolve("sample.jfr");
        try (Recording r = new Recording(Configuration.getConfiguration("profile"))) {
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
            r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1)).withStackTrace();
            r.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1)).withStackTrace();
            r.enable("jdk.JavaExceptionThrow").withStackTrace();
            r.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofMillis(200));
            r.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(200));
            r.enable("jdk.GCHeapMemoryUsage").withPeriod(Duration.ofMillis(200));
            r.start();
            SampleWorkload.run(2_500);
            r.stop();
            r.dump(file);
        }
        // deleteOnExit runs in reverse registration order: the file goes first, then its now-empty directory.
        dir.toFile().deleteOnExit();
        file.toFile().deleteOnExit();
        return file;
    }
}
