package com.springaimcpservercommon.loadtest.maven;

import com.springaimcpservercommon.loadtest.api.LoadTestGenerator;
import com.springaimcpservercommon.loadtest.data.SeedPlan;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * {@code mvn loadtest:discover}: lists the APIs, request fields and the seeding order the generator finds, without
 * writing anything.
 */
@Mojo(name = "discover", requiresProject = false, threadSafe = true)
public class DiscoverMojo extends AbstractGeneratorMojo {

    /** Creates the goal (instantiated by Maven). */
    public DiscoverMojo() {
    }

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().info("loadtest: skipped");
            return;
        }
        LoadTestGenerator.DiscoveryResult d;
        try {
            d = builder().noDatabase().build().discover();
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
        getLog().info(String.format("%-36s %-7s %s", "API", "METHOD", "PATH"));
        for (ApiEndpoint e : d.catalog().endpoints()) {
            getLog().info(String.format("%-36s %-7s %s", e.id(), e.method(), e.path()));
        }
        getLog().info(d.catalog().endpoints().size() + " APIs, " + d.plan().fields().size() + " fields, "
                + d.plan().pools().size() + " real-data bindings");
        int i = 1;
        for (SeedPlan.Step s : d.seed().steps()) {
            getLog().info(String.format("seed %d. %-20s via %s%s", i++, s.table(), s.api(),
                    s.dependsOn().isEmpty() ? "" : " (needs " + String.join(", ", s.dependsOn()) + ")"));
        }
    }
}
