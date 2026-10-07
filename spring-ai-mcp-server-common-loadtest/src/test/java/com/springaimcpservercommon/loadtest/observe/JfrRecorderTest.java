package com.springaimcpservercommon.loadtest.observe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/** A real JVM recorded through jcmd (the one the test starts), with the analyzer replaced by a stub script. */
class JfrRecorderTest {

    private static boolean jcmdAvailable() {
        try {
            Process p = new ProcessBuilder("jcmd", "-l").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void recordsAJvmDumpsItAndHandsItToTheAnalyzer(@TempDir Path dir) throws Exception {
        assumeThat(jcmdAvailable()).as("jcmd on the PATH").isTrue();
        Path source = dir.resolve("Spin.java");
        Files.writeString(source, """
                public class Spin {
                    public static void main(String[] a) throws Exception {
                        long end = System.currentTimeMillis() + 60_000;
                        double x = 0;
                        while (System.currentTimeMillis() < end) { x += Math.sqrt(x + 1); }
                        System.out.println(x);
                    }
                }
                """);
        Path stub = dir.resolve("analyze.sh");
        Files.writeString(stub, "#!/usr/bin/env bash\necho \"analyzed $1\"\n");
        stub.toFile().setExecutable(true);
        System.setProperty("loadtest.jfr.analyze", stub.toString());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process jvm = new ProcessBuilder(java, source.toString()).redirectErrorStream(true).start();
        try {
            Thread.sleep(2000); // the source launcher compiles first
            List<String> log = new ArrayList<>();
            var settings = new JfrRecorder.Settings(String.valueOf(jvm.pid()), null, "jcmd", "profile", dir.resolve("out"),
                    List.of("com.acme"));
            JfrRecorder recorder = JfrRecorder.start(settings, "smoke-2026", log::add);
            Thread.sleep(1500);
            Optional<Path> recording = recorder.stop();
            assertThat(recording).isPresent();
            assertThat(recording.get()).exists().hasFileName("loadtest-smoke-2026.jfr");
            assertThat(Files.size(recording.get())).isGreaterThan(1000);
            assertThat(log).anyMatch(l -> l.startsWith("jfr: recording JVM " + jvm.pid()))
                    .anyMatch(l -> l.startsWith("jfr: analyzed " + recording.get()));
        } finally {
            jvm.destroyForcibly();
            System.clearProperty("loadtest.jfr.analyze");
        }
    }

    @Test
    void refusesWhenTheJvmIsAmbiguousOrMissing(@TempDir Path dir) {
        var none = new JfrRecorder.Settings(null, "no-such-main-class-xyz", "jcmd", "profile", dir, List.of());
        assertThatThrownBy(() -> JfrRecorder.findPid(none)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no JVM found");
    }
}
