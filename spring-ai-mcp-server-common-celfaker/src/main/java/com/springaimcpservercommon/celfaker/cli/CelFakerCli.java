package com.springaimcpservercommon.celfaker.cli;

import com.springaimcpservercommon.celfaker.contract.ApiContract;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import com.springaimcpservercommon.celfaker.payload.PayloadAnalyzer;
import com.springaimcpservercommon.celfaker.pipeline.FakerPipeline;
import com.springaimcpservercommon.celfaker.server.DashboardServer;
import com.springaimcpservercommon.celfaker.values.AttributeValueMap;
import com.springaimcpservercommon.celfaker.workflow.Workflow;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command line: {@code analyze}, {@code generate}, {@code example} and {@code serve} (the flow dashboard).
 * Run through {@code scripts/celfaker.sh}.
 */
public final class CelFakerCli {

    private CelFakerCli() {
    }

    /**
     * Entry point.
     *
     * @param args command and options
     * @throws Exception on I/O failure
     */
    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs a command.
     *
     * @param args command and options
     * @param out  standard output
     * @param err  standard error
     * @return process exit code
     * @throws IOException on I/O failure
     */
    public static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            err.println(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        Map<String, String> opt = options(args);
        switch (args[0]) {
            case "example" -> {
                try (InputStream in = CelFakerCli.class.getResourceAsStream("/celfaker/examples/shop-contract.json")) {
                    out.println(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                return 0;
            }
            case "analyze" -> {
                var payload = JsonValues.MAPPER.readTree(Files.readString(required(opt, "payload")));
                var analysis = PayloadAnalyzer.analyze(payload, opt.getOrDefault("object", "root"));
                out.println(JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(analysis));
                return 0;
            }
            case "generate" -> {
                ApiContract contract = ApiContract.fromJson(Files.readString(required(opt, "contract")));
                Workflow workflow = opt.containsKey("workflow") ? Workflow.fromJson(Files.readString(Path.of(opt.get("workflow")))) : null;
                AttributeValueMap map = opt.containsKey("value-map") ? AttributeValueMap.fromJson(Files.readString(Path.of(opt.get("value-map")))) : null;
                FakerPipeline.Request d = FakerPipeline.Request.of(contract);
                FakerPipeline.Output result = FakerPipeline.run(new FakerPipeline.Request(contract, workflow, map,
                        opt.containsKey("seed") ? Long.parseLong(opt.get("seed")) : d.seed(),
                        opt.containsKey("valid") ? Integer.parseInt(opt.get("valid")) : d.validCount(),
                        d.expressionOptions(),
                        opt.containsKey("cases") ? Integer.parseInt(opt.get("cases")) : d.casesPerExpression()));
                Path dir = Path.of(opt.getOrDefault("out", "celfaker-out"));
                for (Map.Entry<String, String> f : result.files().entrySet()) {
                    Path target = dir.resolve(f.getKey()).normalize();
                    if (!target.startsWith(dir.normalize())) {
                        throw new IOException("refusing to write outside the output directory: " + f.getKey());
                    }
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, f.getValue());
                }
                out.println("wrote " + result.files().size() + " files to " + dir + "  " + result.summary());
                result.warnings().forEach(w -> err.println("warning: " + w));
                out.println("run:  k6 run -e BASE_URL=" + contract.baseUrl() + " " + dir.resolve("k6/main.js"));
                return 0;
            }
            case "serve" -> {
                try (DashboardServer server = new DashboardServer(Integer.parseInt(opt.getOrDefault("port", "8099")))) {
                    out.println("flow dashboard: http://localhost:" + server.port() + "/   (Ctrl+C to stop)");
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
            default -> {
                err.println("unknown command " + args[0] + "\n" + USAGE);
                return 2;
            }
        }
    }

    private static Path required(Map<String, String> opt, String name) {
        String v = opt.get(name);
        if (v == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return Path.of(v);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                m.put(args[i].substring(2), i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true");
            }
        }
        return m;
    }

    private static final String USAGE = """
            usage: celfaker <command> [options]
              example                                   print an example API contract
              analyze  --payload f.json [--object o]    list the parameters (object.attribute, CEL type) of a payload
              generate --contract c.json [--workflow w.json] [--value-map m.json] [--out dir] [--seed n] [--valid n] [--cases n]
                                                        parameters, attribute map, CEL expressions + cases, API data, k6 suite
              serve    [--port 8099]                    the drag-and-drop flow dashboard (loopback only)
            """;
}
