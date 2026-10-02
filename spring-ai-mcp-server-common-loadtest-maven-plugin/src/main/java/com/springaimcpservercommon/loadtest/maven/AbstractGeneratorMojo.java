package com.springaimcpservercommon.loadtest.maven;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Options shared by the goals that read the project ({@code discover}, {@code generate}); each maps to the
 * {@link LoadTestGenerator.Builder} option of the same name and can be set in the POM or with
 * {@code -Dloadtest.<name>=…}.
 */
public abstract class AbstractGeneratorMojo extends AbstractMojo {

    /** The Spring Boot project to read (sources, application.yml, JPA entities, DDL, bundled OpenAPI). */
    @Parameter(defaultValue = "${project.basedir}", property = "loadtest.project")
    protected @Nullable File project;

    /** OpenAPI documents (URLs or files); default: specs bundled in the project. */
    @Parameter(property = "loadtest.openApi")
    protected List<String> openApi = new ArrayList<>();

    /** Whether OpenAPI documents bundled in the project are read when {@code openApi} is empty. */
    @Parameter(defaultValue = "true", property = "loadtest.bundledOpenApi")
    protected boolean bundledOpenApi = true;

    /** {@code /actuator/mappings} of the running application (URL or file). */
    @Parameter(property = "loadtest.actuator")
    protected @Nullable String actuator;

    /** Browser recordings (DevTools ▸ Network ▸ Export HAR). */
    @Parameter(property = "loadtest.har")
    protected List<String> har = new ArrayList<>();

    /** Keep only matching APIs ({@code /api/**}, {@code GET /orders/*}, or an API id). */
    @Parameter(property = "loadtest.includes")
    protected List<String> includes = new ArrayList<>();

    /** Drop matching APIs. */
    @Parameter(property = "loadtest.excludes")
    protected List<String> excludes = new ArrayList<>();

    /** Target base URL including the context path (default {@code http://localhost:<server.port><context>}). */
    @Parameter(property = "loadtest.baseUrl")
    protected @Nullable String baseUrl;

    /** Headers for URL fetches and harvesting. */
    @Parameter
    protected Map<String, String> headers = Map.of();

    /** Skip the goal. */
    @Parameter(defaultValue = "false", property = "loadtest.skip")
    protected boolean skip;

    /**
     * The builder configured from the discovery options.
     *
     * @return a builder; subclasses add their own options
     */
    protected LoadTestGenerator.Builder builder() {
        LoadTestGenerator.Builder b = LoadTestGenerator.builder().log(line -> getLog().info(line));
        if (project != null) {
            b.project(project.toPath());
        }
        openApi.forEach(b::openApi);
        b.bundledOpenApi(bundledOpenApi);
        if (actuator != null && !actuator.isBlank()) {
            b.actuator(actuator);
        }
        har.forEach(b::har);
        includes.forEach(b::include);
        excludes.forEach(b::exclude);
        if (baseUrl != null && !baseUrl.isBlank()) {
            b.baseUrl(baseUrl);
        }
        headers.forEach(b::header);
        return b;
    }
}
