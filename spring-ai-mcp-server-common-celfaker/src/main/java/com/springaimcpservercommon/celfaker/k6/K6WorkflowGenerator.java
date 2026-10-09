package com.springaimcpservercommon.celfaker.k6;

import com.springaimcpservercommon.celfaker.contract.ApiContract;
import com.springaimcpservercommon.celfaker.contract.ApiRole;
import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import com.springaimcpservercommon.celfaker.data.ApiData;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import com.springaimcpservercommon.celfaker.workflow.Load;
import com.springaimcpservercommon.celfaker.workflow.Step;
import com.springaimcpservercommon.celfaker.workflow.Workflow;
import com.springaimcpservercommon.celfaker.workflow.WorkflowPlanner;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the k6 project that runs a workflow: {@code main.js} (options, scenarios, metrics), the shared
 * {@code lib/runtime.js}, {@code workflow.json}, {@code apis.json} and one valid and one invalid data file per API.
 *
 * <p>Two scenarios: {@code flow} runs the whole workflow with valid data under the chosen load profile, passing the
 * extracted variables of each step to the next; {@code negative} sends every invalid body (after running the steps it
 * depends on) and expects the API's rejection status.
 */
public final class K6WorkflowGenerator {

    private K6WorkflowGenerator() {
    }

    /**
     * Generates the project files.
     *
     * @param contract the APIs
     * @param workflow the workflow
     * @param data     generated data per API id
     * @return relative path to file content (UTF-8 text), all under one folder the caller chooses
     * @throws IllegalArgumentException when the workflow is not runnable
     */
    public static Map<String, String> generate(ApiContract contract, Workflow workflow, Map<String, ApiData> data) {
        List<String> problems = WorkflowPlanner.validate(workflow, contract);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("workflow is not runnable: " + String.join("; ", problems));
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/runtime.js", resource("/META-INF/resources/celfaker/ui/runtime.js"));
        files.put("workflow.json", pretty(JsonValues.MAPPER.valueToTree(workflow)));

        ObjectNode apisDoc = apiDocument(contract, workflow);
        StringBuilder validPool = new StringBuilder();
        StringBuilder invalidPool = new StringBuilder();
        int negativeTotal = 0;
        for (ApiSpec a : usedApis(contract, workflow)) {
            ApiData d = data.get(a.id());
            if (a.hasBody() && d != null) {
                ArrayNode valid = JsonValues.MAPPER.createArrayNode();
                d.valid().forEach(valid::add);
                files.put("data/" + a.id() + ".valid.json", pretty(valid));
                files.put("data/" + a.id() + ".invalid.json", pretty(JsonValues.MAPPER.valueToTree(d.invalid())));
                validPool.append("  ").append(JsonValues.MAPPER.writeValueAsString(a.id()))
                        .append(": new SharedArray('valid-").append(a.id()).append("', () => JSON.parse(open('./data/")
                        .append(a.id()).append(".valid.json'))),\n");
                invalidPool.append("  ").append(JsonValues.MAPPER.writeValueAsString(a.id()))
                        .append(": new SharedArray('invalid-").append(a.id()).append("', () => JSON.parse(open('./data/")
                        .append(a.id()).append(".invalid.json'))),\n");
                negativeTotal += d.invalid().size();
            }
        }
        files.put("apis.json", pretty(apisDoc));
        int negatives = Math.min(negativeTotal, workflow.load().negatives());
        files.put("main.js", main(workflow, validPool.toString(), invalidPool.toString(), negatives));
        files.put("README.md", readme(contract, workflow, negatives));
        return files;
    }

    /**
     * The {@code apis.json} document: the APIs a workflow uses, as the runtime reads them (shared by the k6 suite and the
     * dashboard's scenario runner).
     *
     * @param contract the APIs
     * @param workflow the workflow
     * @return {@code {baseUrl, apis: {id: {...}}}}
     */
    public static ObjectNode apiDocument(ApiContract contract, Workflow workflow) {
        ObjectNode doc = JsonValues.MAPPER.createObjectNode();
        doc.put("baseUrl", contract.baseUrl());
        ObjectNode apis = doc.putObject("apis");
        for (ApiSpec a : usedApis(contract, workflow)) {
            ObjectNode n = apis.putObject(a.id());
            n.put("id", a.id());
            n.put("name", a.name());
            n.put("method", a.method());
            n.put("path", a.path());
            n.put("role", a.role().name());
            n.put("description", a.description());
            n.put("hasBody", a.hasBody());
            n.set("headers", JsonValues.MAPPER.valueToTree(a.headers()));
            n.set("expectedStatus", JsonValues.MAPPER.valueToTree(a.expectedStatus()));
            n.set("invalidStatus", JsonValues.MAPPER.valueToTree(a.invalidStatus()));
            if (a.role() == ApiRole.VALIDATION && !a.expectBody().isMissingNode()) {
                n.set("expectBody", a.expectBody());
            }
        }
        return doc;
    }

    private static List<ApiSpec> usedApis(ApiContract contract, Workflow workflow) {
        return contract.apis().stream()
                .filter(a -> workflow.steps().stream().anyMatch(s -> s.api().equals(a.id()))).toList();
    }

    private static String main(Workflow workflow, String validPool, String invalidPool, int negatives) {
        ObjectNode options = JsonValues.MAPPER.createObjectNode();
        ObjectNode scenarios = options.putObject("scenarios");
        scenarios.set("flow", flowScenario(workflow.load()));
        if (negatives > 0) {
            ObjectNode neg = scenarios.putObject("negative");
            neg.put("executor", "shared-iterations");
            neg.put("exec", "negative");
            neg.put("vus", Math.min(5, Math.max(1, workflow.load().vus())));
            neg.put("iterations", negatives);
            neg.put("maxDuration", "10m");
        }
        ObjectNode thresholds = options.putObject("thresholds");
        thresholds.putArray("checks").add("rate>0.99");
        thresholds.putArray("workflow_ok").add("rate>0.99");
        thresholds.putArray("http_req_failed{kind:flow}").add("rate<0.01");
        thresholds.putArray("http_req_duration{kind:flow}").add("p(95)<1500");
        return """
                // Generated by springAIMcpServerCommon celfaker (ADR-0030). Run:
                //   k6 run -e BASE_URL=http://localhost:8080 main.js
                // Scenarios: flow (workflow, valid data, load profile) and negative (invalid data must be rejected).
                import http from 'k6/http';
                import { check, sleep } from 'k6';
                import exec from 'k6/execution';
                import { SharedArray } from 'k6/data';
                import { Rate, Counter } from 'k6/metrics';
                import { createRuntime } from './lib/runtime.js';

                const workflow = JSON.parse(open('./workflow.json'));
                const apiDoc = JSON.parse(open('./apis.json'));

                const valid = {
                %s};
                const invalid = {
                %s};

                const workflowOk = new Rate('workflow_ok');
                const setupFailed = new Counter('negative_setup_failed');

                const runtime = createRuntime({
                  http, check, sleep, workflow, apis: apiDoc.apis, valid, invalid,
                  baseUrl: (__ENV.BASE_URL || apiDoc.baseUrl).replace(/\\/$/, ''),
                  env: __ENV,
                  metrics: { setupFailed },
                  log: (m) => console.warn(m),
                });

                export const options = %s;

                export async function flow() {
                  workflowOk.add(await runtime.runFlow({ vu: __VU, iter: __ITER }));
                }

                export async function negative() {
                  await runtime.runNegative(exec.scenario.iterationInTest, { vu: __VU, iter: exec.scenario.iterationInTest });
                }
                """.formatted(validPool, invalidPool, pretty(options));
    }

    private static ObjectNode flowScenario(Load load) {
        ObjectNode s = JsonValues.MAPPER.createObjectNode();
        s.put("exec", "flow");
        int vus = load.vus();
        switch (load.profile()) {
            case "load" -> stages(s, vus, new int[][]{{50, 1}, {100, 3}, {100, 1}, {0, 1}}, load.duration());
            case "stress" -> stages(s, vus, new int[][]{{25, 1}, {50, 1}, {100, 1}, {150, 1}, {0, 1}}, load.duration());
            case "spike" -> stages(s, vus, new int[][]{{10, 1}, {10, 1}, {100, 0}, {100, 1}, {10, 0}, {10, 1}, {0, 1}}, load.duration());
            case "custom" -> {
                s.put("executor", "constant-vus");
                s.put("vus", vus);
                s.put("duration", load.duration());
            }
            default -> {
                s.put("executor", "shared-iterations");
                s.put("vus", 1);
                s.put("iterations", 1);
            }
        }
        return s;
    }

    /** Ramping stages: percent of peak VUs and a weight of the configured duration (0 = 5 s ramp). */
    private static void stages(ObjectNode s, int vus, int[][] spec, String duration) {
        s.put("executor", "ramping-vus");
        s.put("startVUs", 0);
        s.put("gracefulRampDown", "30s");
        ArrayNode stages = s.putArray("stages");
        for (int[] st : spec) {
            ObjectNode n = stages.addObject();
            n.put("duration", st[1] == 0 ? "5s" : scale(duration, st[1]));
            n.put("target", Math.max(st[0] == 0 ? 0 : 1, vus * st[0] / 100));
        }
    }

    private static String scale(String duration, int factor) {
        String num = duration.replaceAll("[^0-9]", "");
        String unit = duration.replaceAll("[0-9]", "");
        return (Integer.parseInt(num.isEmpty() ? "30" : num) * factor) + (unit.isEmpty() ? "s" : unit);
    }

    private static String readme(ApiContract contract, Workflow workflow, int negatives) {
        StringBuilder sb = new StringBuilder("# ").append(contract.name()).append(" — k6 workflow suite\n\n");
        sb.append("Generated by springAIMcpServerCommon celfaker. Run with\n\n```\nk6 run -e BASE_URL=")
                .append(contract.baseUrl()).append(" main.js\n```\n\n");
        sb.append("Pass secrets as `-e NAME=value` and reference them as `{{env.NAME}}` in API headers or step injections.\n\n");
        sb.append("## Scenarios\n\n- **flow** — runs the workflow (profile `").append(workflow.load().profile())
                .append("`, ").append(workflow.load().vus()).append(" VUs) with valid data; variables extracted from one response are injected into later requests.\n");
        sb.append("- **negative** — ").append(negatives).append(" invalid requests (wrong type, null, missing field, violated rule); each must be rejected with the API's `invalidStatus`.\n\n");
        sb.append("## Workflow steps\n\n| Step | API | Depends on | Extracts | Injects |\n|---|---|---|---|---|\n");
        for (Step s : WorkflowPlanner.order(workflow)) {
            sb.append("| ").append(s.id()).append(" | ").append(s.api()).append(" | ")
                    .append(String.join(", ", s.dependsOn())).append(" | ")
                    .append(s.extract().stream().map(e -> e.name() + " ← " + e.from()).reduce((a, b) -> a + "; " + b).orElse(""))
                    .append(" | ")
                    .append(s.inject().stream().map(i -> i.target() + " = " + i.value()).reduce((a, b) -> a + "; " + b).orElse(""))
                    .append(" |\n");
        }
        sb.append("\n## APIs\n\n");
        for (ApiSpec a : usedApis(contract, workflow)) {
            sb.append("### ").append(a.name()).append(" (`").append(a.id()).append("`)\n\n`")
                    .append(a.method()).append(' ').append(a.path()).append("` — ").append(a.role()).append("\n\n");
            if (!a.description().isBlank()) {
                sb.append(a.description()).append("\n\n");
            }
            if (!a.rules().isEmpty()) {
                sb.append("Rules (CEL):\n\n");
                a.rules().forEach(r -> sb.append("- `").append(r).append("`\n"));
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String pretty(tools.jackson.databind.JsonNode node) {
        return JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private static String resource(String path) {
        try (InputStream in = K6WorkflowGenerator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
