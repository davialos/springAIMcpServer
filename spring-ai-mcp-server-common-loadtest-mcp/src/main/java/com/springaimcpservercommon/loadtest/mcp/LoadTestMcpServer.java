package com.springaimcpservercommon.loadtest.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Stdio MCP server for the load-test generator. Start it from the directory that holds the Spring projects (or pass
 * {@code --root <dir>}): every path a tool receives is resolved against that root and confined to it.
 * <pre>
 * scripts/loadtest-mcp.sh [--root ../workspace]
 * claude mcp add spring-loadtest -- /path/to/springAIMcpServer/scripts/loadtest-mcp.sh
 * </pre>
 * Standard output carries the protocol only; diagnostics go to standard error.
 */
public final class LoadTestMcpServer {

    static final String INSTRUCTIONS = """
            Load testing for Spring Boot projects with Grafana k6. Typical flow: loadtest_discover (what APIs and \
            relationships exist) → loadtest_generate (write the suite) → loadtest_run mode=smoke (must pass first; \
            confirm the target URL with the user, it sends real traffic and seeds rows) → heavier modes \
            (mixed-load, mixed-spike, stress) → loadtest_report / loadtest_compare against a baseline. Fix failures \
            in the suite's hooks.js, data/user.json or loadtest.config.json, never by disabling thresholds.""";

    private LoadTestMcpServer() {
    }

    /**
     * Builds the server on a transport.
     *
     * @param transport transport provider
     * @param tools     the tools
     * @return the running server
     */
    public static McpSyncServer start(StdioServerTransportProvider transport, LoadTestTools tools) {
        List<McpServerFeatures.SyncToolSpecification> specs = tools.tools().stream()
                .map(t -> McpServerFeatures.SyncToolSpecification.builder().tool(t.tool())
                        .callHandler((exchange, request) -> t.handler().apply(
                                request.arguments() == null ? java.util.Map.of() : request.arguments()))
                        .build())
                .toList();
        return McpServer.sync(transport)
                .serverInfo("spring-loadtest", version())
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .requestTimeout(LoadTestTools.MAX_RUN.plus(Duration.ofMinutes(1)))
                .tools(specs)
                .build();
    }

    /**
     * Entry point.
     *
     * @param args {@code --root <dir>} (default: the working directory)
     * @throws InterruptedException when interrupted while serving
     */
    public static void main(String[] args) throws InterruptedException {
        Path root = Path.of("");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--root") && i + 1 < args.length) {
                root = Path.of(args[++i]);
            } else {
                System.err.println("usage: loadtest-mcp [--root <dir>]");
                System.exit(2);
            }
        }
        if (!Files.isDirectory(root)) {
            System.err.println("loadtest-mcp: root " + root + " is not a directory");
            System.exit(2);
        }
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(JsonMapper.builder().build()));
        McpSyncServer server = start(transport, new LoadTestTools(root));
        System.err.println("loadtest-mcp: serving on stdio, root " + root.toAbsolutePath().normalize());
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.closeGracefully();
            done.countDown();
        }));
        done.await();
    }

    private static String version() {
        String v = LoadTestMcpServer.class.getPackage().getImplementationVersion();
        return v == null ? "0.1.0-SNAPSHOT" : v;
    }
}
