package com.springaimcpservercommon.loadtest.k6;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a generated suite with the k6 binary ({@code --k6}, {@code K6_BIN}, or {@code k6} on the PATH).
 */
public final class K6Runner {

    /**
     * A run request.
     *
     * @param suiteDir suite directory
     * @param mode     {@code MODE}
     * @param dataMode {@code DATA_MODE}, or {@code null} for the config's
     * @param env      further variables ({@code API}, {@code VUS}, {@code BASE_URL} …)
     * @param k6       k6 executable, or {@code null} for the default
     * @param extra    extra k6 arguments (e.g. {@code --out json=results.json})
     */
    public record Run(Path suiteDir, String mode, @Nullable String dataMode, Map<String, String> env,
                      @Nullable String k6, List<String> extra) {
    }

    /**
     * The command line for a run.
     *
     * @param run request
     * @return command
     */
    public static List<String> command(Run run) {
        String bin = run.k6() != null ? run.k6() : System.getenv().getOrDefault("K6_BIN", "k6");
        List<String> cmd = new ArrayList<>(List.of(bin, "run", "-e", "MODE=" + run.mode()));
        if (run.dataMode() != null) {
            cmd.add("-e");
            cmd.add("DATA_MODE=" + run.dataMode());
        }
        run.env().forEach((k, v) -> {
            cmd.add("-e");
            cmd.add(k + "=" + v);
        });
        if (run.mode().equals("preview")) {
            cmd.add("--log-format=raw"); // one JSON request per line
            cmd.add("--quiet");
        }
        cmd.addAll(run.extra());
        cmd.add("main.js");
        return cmd;
    }

    /**
     * Runs k6 in the suite directory, streaming its output.
     *
     * @param run request
     * @return k6's exit code (99 = thresholds failed)
     */
    public int run(Run run) {
        ProcessBuilder pb = new ProcessBuilder(command(run)).directory(run.suiteDir().toFile()).inheritIO();
        try {
            return pb.start().waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start k6 (install it or pass --k6 <path>): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }
}
