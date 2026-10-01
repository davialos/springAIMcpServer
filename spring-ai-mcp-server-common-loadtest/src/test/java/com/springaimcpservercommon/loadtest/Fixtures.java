package com.springaimcpservercommon.loadtest;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Shared test fixtures: the sample Spring Boot project and the k6 binary (tests that need k6 are skipped when it
 * is not installed; set {@code K6_BIN} or put {@code k6} on the PATH).
 */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * The sample shop project under {@code src/test/resources/sample-shop}.
     *
     * @return project directory
     */
    public static Path sampleShop() {
        try {
            return Path.of(Fixtures.class.getResource("/sample-shop").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The k6 executable, if installed.
     *
     * @return path to k6
     */
    public static Optional<String> k6() {
        String env = System.getenv("K6_BIN");
        if (env != null && Files.isExecutable(Path.of(env))) {
            return Optional.of(env);
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path candidate = Path.of(dir, "k6");
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate.toString());
            }
        }
        return Optional.empty();
    }
}
