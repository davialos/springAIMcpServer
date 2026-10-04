package com.springaimcpservercommon.loadtest.maven;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code mvn loadtest:generate}: writes (or regenerates) the k6 suite — request builders, data providers,
 * relationship-ordered seeding, load modes, Grafana stack — into {@code outDir}. The team's config, user data and
 * hooks are kept on regeneration.
 */
@Mojo(name = "generate", requiresProject = false, threadSafe = true)
public class GenerateMojo extends AbstractGeneratorMojo {

    /** Suite directory. */
    @Parameter(defaultValue = "${project.basedir}/load-tests", property = "loadtest.outDir")
    protected @Nullable File outDir;

    /** Default data mode: auto, dummy, random, real, user or mixed. */
    @Parameter(defaultValue = "auto", property = "loadtest.dataMode")
    protected String dataMode = "auto";

    /** Read real values from the database ({@code dbUrl}, else the project's {@code spring.datasource.url}). */
    @Parameter(defaultValue = "true", property = "loadtest.database")
    protected boolean database = true;

    /** JDBC URL for real data. */
    @Parameter(property = "loadtest.dbUrl")
    protected @Nullable String dbUrl;

    /** Database user. */
    @Parameter(property = "loadtest.dbUser")
    protected @Nullable String dbUser;

    /** Database password (prefer {@code LOADTEST_DB_PASSWORD} in the environment). */
    @Parameter(property = "loadtest.dbPassword")
    protected @Nullable String dbPassword;

    /** Real values sampled per pool. */
    @Parameter(defaultValue = "200", property = "loadtest.sampleSize")
    protected int sampleSize = 200;

    /** Also fill real pools from the running API's collection endpoints. */
    @Parameter(defaultValue = "false", property = "loadtest.harvest")
    protected boolean harvest;

    /** User data files: JSON/YAML {@code {fields, payloads, bindings}} or CSV. */
    @Parameter
    protected List<File> userData = new ArrayList<>();

    /** User values per field key, comma-separated ({@code <CreateOrderRequest.couponCode>A,B</…>}). */
    @Parameter
    protected Map<String, String> values = Map.of();

    /** Field key → {@code table.column} bindings. */
    @Parameter
    protected Map<String, String> bindings = Map.of();

    /** Drop user values of id/FK fields that are not in the database. */
    @Parameter(defaultValue = "false", property = "loadtest.dropUnverified")
    protected boolean dropUnverified;

    /** Authentication: none, bearer, basic, apiKey or login. */
    @Parameter(defaultValue = "none", property = "loadtest.auth")
    protected String auth = "none";

    /** Login path for {@code auth = login}. */
    @Parameter(property = "loadtest.loginPath")
    protected @Nullable String loginPath;

    /** Creates the goal (instantiated by Maven). */
    public GenerateMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("loadtest: skipped");
            return;
        }
        LoadTestGenerator.Builder b = builder().dataMode(dataMode).sampleSize(sampleSize).harvest(harvest)
                .dropUnverified(dropUnverified).auth(auth, loginPath);
        if (outDir != null) {
            b.outDir(outDir.toPath());
        }
        if (!database) {
            b.noDatabase();
        } else if (dbUrl != null && !dbUrl.isBlank()) {
            b.database(dbUrl, dbUser, dbPassword);
        }
        userData.forEach(f -> b.userData(f.toPath()));
        values.forEach((k, v) -> b.value(k, Arrays.stream(v.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(GenerateMojo::typed).toList()));
        bindings.forEach(b::bind);
        try {
            LoadTestGenerator.GenerationResult r = b.build().generate();
            getLog().info("Generated k6 suite in " + r.outDir().toAbsolutePath().normalize() + ": " + r.apis()
                    + " APIs, " + r.fields() + " fields, " + r.pools() + " real-data pools, "
                    + r.seed().steps().size() + " seeded tables");
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (UncheckedIOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private static Object typed(String v) {
        return v.matches("-?\\d{1,18}") ? Long.valueOf(v) : v;
    }
}
