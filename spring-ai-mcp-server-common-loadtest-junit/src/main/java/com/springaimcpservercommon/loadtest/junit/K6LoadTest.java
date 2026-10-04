package com.springaimcpservercommon.loadtest.junit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a k6 load-test suite for a Spring project once per test class and lets test methods run it against
 * the application under test, through a {@link K6Suite} parameter:
 * <pre>{@code
 * @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
 * @K6LoadTest
 * class ShopLoadTest {
 *     @LocalServerPort int port;     // read by name: no Spring dependency
 *
 *     @Test
 *     void smoke(K6Suite suite) {
 *         suite.assertPassed("smoke");                 // every API, seeded data, thresholds must pass
 *     }
 * }
 * }</pre>
 * The target is {@link #baseUrl()}, else the {@code loadtest.baseUrl} system property, else
 * {@code http://localhost:<port><context path>} with the port from a field annotated {@code @LocalServerPort} or
 * {@link K6Target}. Without a k6 executable ({@code K6_BIN} or {@code PATH}) the tests are skipped, unless
 * {@link #requireK6()}. Tagged {@code load-test}, so builds can include or exclude these tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("load-test")
@ExtendWith(K6LoadTestExtension.class)
public @interface K6LoadTest {

    /**
     * The Spring project to read, relative to the working directory (the module directory under Maven/Gradle).
     *
     * @return project directory
     */
    String project() default ".";

    /**
     * Where the suite is written.
     *
     * @return suite directory
     */
    String outDir() default "target/load-tests";

    /**
     * Keep only matching APIs ({@code /api/**}, {@code GET /orders/*}, or an API id).
     *
     * @return include patterns
     */
    String[] include() default {};

    /**
     * Drop matching APIs.
     *
     * @return exclude patterns
     */
    String[] exclude() default {};

    /**
     * Sample real values from the project's {@code spring.datasource.*} during generation; off by default (tests
     * create their own data through seeding).
     *
     * @return whether to read the database
     */
    boolean database() default false;

    /**
     * Explicit target base URL; empty to derive it from the test's port.
     *
     * @return base URL
     */
    String baseUrl() default "";

    /**
     * Suite variables for every run, as {@code NAME=value} ({@code SEED_PER_TABLE=2}, {@code DURATION_SCALE=0.1}).
     *
     * @return variables
     */
    String[] env() default {};

    /**
     * Fail instead of skipping when no k6 executable is found.
     *
     * @return whether k6 is required
     */
    boolean requireK6() default false;
}
