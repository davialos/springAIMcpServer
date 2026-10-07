package com.springaimcpservercommon.loadtest.maven;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * {@code mvn loadtest:schema}: reads the current structure of the configured database (tables, columns, keys,
 * indexes, views, sequences, enum types) over a read-only connection and writes it as DDL. Structure only: no row
 * data is read, apart from the optional row counts.
 */
@Mojo(name = "schema", requiresProject = false, threadSafe = true)
public class SchemaMojo extends AbstractMojo {

    /** The Spring Boot project whose {@code spring.datasource.*} names the database (when no {@code dbUrl}). */
    @Parameter(defaultValue = "${project.basedir}", property = "loadtest.project")
    protected @Nullable File project;

    /** JDBC URL; default: the project's {@code spring.datasource.url}. */
    @Parameter(property = "loadtest.dbUrl")
    protected @Nullable String dbUrl;

    /** Database user; default: the project's. */
    @Parameter(property = "loadtest.dbUser")
    protected @Nullable String dbUser;

    /** Database password; default: {@code LOADTEST_DB_PASSWORD}, else the project's. */
    @Parameter(property = "loadtest.dbPassword")
    protected @Nullable String dbPassword;

    /** Only this database schema (default: every non-system schema). */
    @Parameter(property = "loadtest.dbSchema")
    protected @Nullable String dbSchema;

    /** Add an exact row count per table as a comment (can be slow on big tables). */
    @Parameter(defaultValue = "false", property = "loadtest.rowCounts")
    protected boolean rowCounts;

    /** Where the DDL is written. */
    @Parameter(defaultValue = "${project.build.directory}/loadtest/schema.sql", property = "loadtest.schemaFile")
    protected @Nullable File outFile;

    /** Skip the goal. */
    @Parameter(defaultValue = "false", property = "loadtest.skip")
    protected boolean skip;

    /** Creates the goal (instantiated by Maven). */
    public SchemaMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("loadtest: skipped");
            return;
        }
        LoadTestGenerator.Builder b = LoadTestGenerator.builder().log(line -> getLog().info(line));
        if (project != null && Files.isDirectory(project.toPath())) {
            b.project(project.toPath());
        }
        if (dbUrl != null && !dbUrl.isBlank()) {
            b.database(dbUrl, dbUser, dbPassword);
        }
        if (dbSchema != null && !dbSchema.isBlank()) {
            b.databaseSchema(dbSchema);
        }
        LoadTestGenerator generator = b.build();
        try {
            SchemaSnapshot snapshot = generator.readSchema(rowCounts);
            String ddl = generator.ddl(snapshot);
            if (outFile == null) {
                getLog().info(ddl);
                return;
            }
            Path file = outFile.toPath();
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, ddl);
            getLog().info("Database structure written to " + file.toAbsolutePath().normalize());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (SQLException e) {
            throw new MojoExecutionException("database error: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
