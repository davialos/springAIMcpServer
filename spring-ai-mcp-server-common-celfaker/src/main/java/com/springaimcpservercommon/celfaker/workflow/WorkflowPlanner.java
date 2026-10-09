package com.springaimcpservercommon.celfaker.workflow;

import com.springaimcpservercommon.celfaker.contract.ApiContract;
import com.springaimcpservercommon.celfaker.contract.ApiRole;
import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Orders and validates workflows, and proposes a default one when the user draws none. */
public final class WorkflowPlanner {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\[\\]-]+)\\s*}}");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");
    private static final Set<String> BUILTIN = Set.of("iter", "vu", "uuid", "timestamp");

    private WorkflowPlanner() {
    }

    /**
     * Topological order of the steps (dependencies first, list order breaks ties).
     *
     * @param workflow the workflow
     * @return ordered steps
     * @throws IllegalArgumentException on a cycle or an unknown dependency
     */
    public static List<Step> order(Workflow workflow) {
        Map<String, Step> byId = new HashMap<>();
        workflow.steps().forEach(s -> byId.put(s.id(), s));
        List<Step> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (Step s : workflow.steps()) {
            visit(s, byId, done, visiting, out);
        }
        return out;
    }

    private static void visit(Step s, Map<String, Step> byId, Set<String> done, Set<String> visiting, List<Step> out) {
        if (done.contains(s.id())) {
            return;
        }
        if (!visiting.add(s.id())) {
            throw new IllegalArgumentException("cycle through step " + s.id());
        }
        for (String dep : s.dependsOn()) {
            Step d = byId.get(dep);
            if (d == null) {
                throw new IllegalArgumentException("step " + s.id() + " depends on unknown step " + dep);
            }
            visit(d, byId, done, visiting, out);
        }
        visiting.remove(s.id());
        done.add(s.id());
        out.add(s);
    }

    /**
     * Checks a workflow against the contract and returns every problem found.
     *
     * @param workflow the workflow
     * @param contract the APIs
     * @return problems (empty = runnable)
     */
    public static List<String> validate(Workflow workflow, ApiContract contract) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Step s : workflow.steps()) {
            if (!ids.add(s.id())) {
                problems.add("duplicate step id " + s.id());
            }
            if (contract.find(s.api()) == null) {
                problems.add("step " + s.id() + " calls unknown api " + s.api());
            }
        }
        List<Step> ordered;
        try {
            ordered = order(workflow);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return problems;
        }
        Map<String, Step> byId = new HashMap<>();
        workflow.steps().forEach(s -> byId.put(s.id(), s));
        for (Step s : ordered) {
            Set<String> ancestors = ancestors(s, byId);
            Set<String> extracted = new HashSet<>();
            for (Extract e : s.extract()) {
                if (e.name() == null || !e.name().matches("[A-Za-z][A-Za-z0-9_]*")) {
                    problems.add("step " + s.id() + ": bad variable name " + e.name());
                } else if (!extracted.add(e.name())) {
                    problems.add("step " + s.id() + ": variable " + e.name() + " extracted twice");
                }
                if (e.from() == null || !(e.from().equals("status") || e.from().startsWith("body.") || e.from().startsWith("header."))) {
                    problems.add("step " + s.id() + ": extract source must be status, body.<path> or header.<Name>: " + e.from());
                }
            }
            ApiSpec api = contract.find(s.api());
            Set<String> pathParams = new HashSet<>();
            if (api != null) {
                Matcher pm = PATH_PARAM.matcher(api.path());
                while (pm.find()) {
                    pathParams.add(pm.group(1));
                }
            }
            for (Assertion a : s.assertions()) {
                if (!Assertion.OPS.contains(a.op())) {
                    problems.add("step " + s.id() + ": unknown assertion operator " + a.op());
                }
                if (a.from() == null || !(a.from().equals("status") || a.from().startsWith("body.") || a.from().startsWith("header."))) {
                    problems.add("step " + s.id() + ": assertion source must be status, body.<path> or header.<Name>: " + a.from());
                }
                checkRefs(s, a.value(), ancestors, byId, problems);
            }
            if (api != null && !api.hasBody() && (s.body() != null || !s.invalidCase().isEmpty())) {
                problems.add("step " + s.id() + ": api " + api.id() + " has no JSON request body, so a custom or invalid body is meaningless");
            }
            if (s.body() != null && !s.body().isObject()) {
                problems.add("step " + s.id() + ": the custom body must be a JSON object");
            }
            Set<String> injectedPath = new HashSet<>();
            for (Inject in : s.inject()) {
                if (in.target() == null || !in.target().matches("(path|query|header|body)\\..+")) {
                    problems.add("step " + s.id() + ": inject target must be path.<n>, query.<n>, header.<N> or body.<path>: " + in.target());
                    continue;
                }
                if (in.target().startsWith("path.")) {
                    injectedPath.add(in.target().substring(5));
                }
                checkRefs(s, in.value(), ancestors, byId, problems);
            }
            for (String pp : pathParams) {
                if (!injectedPath.contains(pp)) {
                    problems.add("step " + s.id() + ": path parameter {" + pp + "} has no value (add an inject path." + pp + ")");
                }
            }
        }
        return problems;
    }

    private static void checkRefs(Step s, String text, Set<String> ancestors, Map<String, Step> byId, List<String> problems) {
        Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
        while (m.find()) {
            String ref = m.group(1);
            if (BUILTIN.contains(ref) || ref.startsWith("env.")) {
                continue;
            }
            int dot = ref.indexOf('.');
            String stepId = dot < 0 ? ref : ref.substring(0, dot);
            String var = dot < 0 ? "" : ref.substring(dot + 1);
            if (!ancestors.contains(stepId)) {
                problems.add("step " + s.id() + ": {{" + ref + "}} refers to a step that does not run before it");
            } else if (byId.get(stepId).extract().stream().noneMatch(e -> e.name().equals(var))) {
                problems.add("step " + s.id() + ": step " + stepId + " does not extract " + var);
            }
        }
    }

    private static Set<String> ancestors(Step s, Map<String, Step> byId) {
        Set<String> out = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(s.dependsOn());
        while (!todo.isEmpty()) {
            String id = todo.removeLast();
            if (out.add(id) && byId.containsKey(id)) {
                todo.addAll(byId.get(id).dependsOn());
            }
        }
        return out;
    }

    /**
     * A default workflow: actions in contract order, each validation API after the action it validates, ids taken from
     * the action's response example and injected into the validation's path.
     *
     * @param contract the APIs
     * @return the proposed workflow
     */
    public static Workflow propose(ApiContract contract) {
        List<Step> steps = new ArrayList<>();
        Map<String, String> stepOfApi = new HashMap<>();
        double x = 40;
        String previous = null;
        for (ApiSpec a : contract.apis()) {
            if (a.role() != ApiRole.ACTION) {
                continue;
            }
            List<Extract> extract = new ArrayList<>();
            for (String candidate : List.of("id", "uuid", "orderId", a.id() + "Id")) {
                JsonNode v = a.responseExample().path(candidate);
                if (!v.isMissingNode() && !v.isNull() && !v.isObject() && !v.isArray()) {
                    extract.add(new Extract(candidate, "body." + candidate));
                    break;
                }
            }
            steps.add(new Step(a.id(), a.id(), previous == null ? List.of() : List.of(previous), extract, List.of(),
                    List.of(), List.of(), null, "", 0.2, x, 80));
            stepOfApi.put(a.id(), a.id());
            previous = a.id();
            x += 220;
        }
        double y = 240;
        for (ApiSpec a : contract.apis()) {
            if (a.role() != ApiRole.VALIDATION) {
                continue;
            }
            String target = a.validates() != null && stepOfApi.containsKey(a.validates()) ? a.validates() : previous;
            List<Inject> inject = new ArrayList<>();
            if (target != null) {
                Step t = steps.stream().filter(s -> s.id().equals(target)).findFirst().orElseThrow();
                Matcher pm = PATH_PARAM.matcher(a.path());
                while (pm.find() && !t.extract().isEmpty()) {
                    inject.add(new Inject("path." + pm.group(1), "{{" + t.id() + "." + t.extract().getFirst().name() + "}}"));
                }
            }
            steps.add(new Step(a.id(), a.id(), target == null ? List.of() : List.of(target), List.of(), inject, List.of(), List.of(), null, "", 0.1,
                    40 + 220.0 * (steps.size() % 4), y));
            y += 40;
        }
        return new Workflow(contract.name() + " flow", steps, Load.smoke());
    }
}
