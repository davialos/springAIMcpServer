package com.springaimcpservercommon.celfaker.data;

import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import com.springaimcpservercommon.celfaker.expr.CaseBuilder;
import com.springaimcpservercommon.celfaker.expr.CelCase;
import com.springaimcpservercommon.celfaker.payload.Candidate;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import com.springaimcpservercommon.celfaker.values.AttributeValueMap;
import com.springaimcpservercommon.celfaker.values.AttributeValues;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates request data for an API from the attribute value map: valid bodies (every rule satisfied, verified by the
 * CEL runtime), and negative bodies (a wrong type, null or missing field; a violated rule).
 */
public final class DataGenerator {

    private static final int MAX_INVALID_VALUES_PER_FIELD = 3;
    private static final int MAX_CASES_PER_RULE = 6;

    private final AttributeValueMap values;
    private final CaseBuilder cases;
    private final long seed;

    /**
     * Creates a generator.
     *
     * @param values value map
     * @param cases  CEL case builder over the same library and map
     * @param seed   seed
     */
    public DataGenerator(AttributeValueMap values, CaseBuilder cases, long seed) {
        this.values = values;
        this.cases = cases;
        this.seed = seed;
    }

    /**
     * Generates the data of one API.
     *
     * @param api        the API
     * @param candidates the body parameters of this API (by CEL name, as in the library)
     * @param validCount number of valid bodies
     * @return the data
     */
    public ApiData generate(ApiSpec api, List<Candidate> candidates, int validCount) {
        List<String> warnings = new ArrayList<>();
        if (!api.hasBody()) {
            return new ApiData(api.id(), List.of(), List.of(), warnings);
        }
        Random rnd = new Random(seed ^ api.id().hashCode());
        Map<String, Candidate> byName = new LinkedHashMap<>();
        candidates.forEach(c -> byName.put(c.celName(), c));

        // 1. rules: group the ones that share parameters; each group is solved on its own (a joint search over every
        //    parameter of the body would almost never hit a combination that satisfies all rules at once)
        List<String> rules = new ArrayList<>();
        Map<String, Set<String>> paramsOf = new LinkedHashMap<>();
        for (String rule : api.rules()) {
            List<CelCase> probe = cases.build(rule, 1);
            if (probe.isEmpty()) {
                warnings.add("rule does not compile against the parameter library: " + rule);
                continue;
            }
            rules.add(rule);
            paramsOf.put(rule, new LinkedHashSet<>(probe.getFirst().inputs().keySet()));
        }
        List<List<Map<String, Object>>> satisfying = new ArrayList<>();
        for (List<String> group : groups(rules, paramsOf)) {
            String all = group.stream().map(r -> "(" + r + ")").collect(Collectors.joining(" && "));
            List<Map<String, Object>> solutions = onlyOwn(cases.build(all, 600).stream().filter(CelCase::isTrue).toList(), byName);
            if (solutions.isEmpty()) {
                warnings.add("no input combination satisfies these rules together; valid data ignores them: " + all);
            } else {
                Collections.shuffle(solutions, rnd);
                satisfying.add(solutions);
            }
        }

        // 2. valid bodies
        Map<String, List<Object>> pools = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            List<Object> pool = new ArrayList<>(values.find(c.celName()).map(AttributeValues::valid).orElse(List.of()));
            Collections.shuffle(pool, rnd);
            pools.put(c.celName(), pool);
        }
        List<JsonNode> valid = new ArrayList<>();
        for (int i = 0; i < validCount; i++) {
            Map<String, Object> facts = new HashMap<>();
            for (Map.Entry<String, List<Object>> e : pools.entrySet()) {
                List<Object> pool = e.getValue();
                if (!pool.isEmpty()) {
                    facts.put(e.getKey(), pool.get(i % pool.size()));
                }
            }
            for (List<Map<String, Object>> solutions : satisfying) {
                facts.putAll(solutions.get(i % solutions.size()));
            }
            valid.add(body(api, byName, facts));
        }
        if (valid.isEmpty()) {
            valid.add(api.requestExample().deepCopy());
        }
        JsonNode base = valid.getFirst();

        // 3. invalid bodies
        List<InvalidCase> invalid = new ArrayList<>();
        for (Candidate c : candidates) {
            AttributeValues av = values.find(c.celName()).orElse(null);
            if (av == null) {
                continue;
            }
            Set<String> kinds = new LinkedHashSet<>();
            for (Object bad : av.invalid()) {
                if (kinds.size() >= MAX_INVALID_VALUES_PER_FIELD || !kinds.add(kind(bad))) {
                    continue;
                }
                ObjectNode copy = ((ObjectNode) base).deepCopy();
                JsonValues.set(copy, c.path(), bad == null ? NullNode.getInstance() : JsonValues.toNode(bad));
                invalid.add(new InvalidCase(c.celName() + ":invalid_" + kind(bad), c.celName(), copy, api.invalidStatus()));
            }
            ObjectNode missing = ((ObjectNode) base).deepCopy();
            JsonValues.remove(missing, c.path());
            invalid.add(new InvalidCase(c.celName() + ":missing", c.celName(), missing, api.invalidStatus()));
        }
        for (String rule : rules) {
            List<Map<String, Object>> violating = onlyOwn(cases.build(rule, 300).stream().filter(CelCase::isFalse).toList(), byName);
            if (violating.isEmpty()) {
                warnings.add("no input combination violates the rule: " + rule);
                continue;
            }
            Collections.shuffle(violating, rnd);
            for (Map<String, Object> facts : violating.subList(0, Math.min(MAX_CASES_PER_RULE, violating.size()))) {
                Map<String, Object> merged = new HashMap<>();
                for (Candidate c : candidates) {
                    merged.put(c.celName(), JsonValues.toJava(JsonValues.at(base, c.path())));
                }
                merged.putAll(facts);
                invalid.add(new InvalidCase("rule:" + rule, String.join(",", facts.keySet()), body(api, byName, merged), api.invalidStatus()));
            }
        }
        return new ApiData(api.id(), valid, invalid, warnings);
    }

    /** Connected components of rules that share at least one parameter. */
    private static List<List<String>> groups(List<String> rules, Map<String, Set<String>> paramsOf) {
        List<List<String>> out = new ArrayList<>();
        List<Set<String>> params = new ArrayList<>();
        for (String rule : rules) {
            Set<String> mine = new LinkedHashSet<>(paramsOf.get(rule));
            List<String> group = new ArrayList<>(List.of(rule));
            for (int i = out.size() - 1; i >= 0; i--) {
                if (!Collections.disjoint(params.get(i), mine)) {
                    group.addAll(0, out.remove(i));
                    mine.addAll(params.remove(i));
                }
            }
            out.add(group);
            params.add(mine);
        }
        return out;
    }

    private static List<Map<String, Object>> onlyOwn(List<CelCase> found, Map<String, Candidate> own) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CelCase c : found) {
            if (own.keySet().containsAll(c.inputs().keySet())) {
                out.add(new LinkedHashMap<>(c.inputs()));
            }
        }
        return out;
    }

    private static JsonNode body(ApiSpec api, Map<String, Candidate> byName, Map<String, Object> facts) {
        ObjectNode body = ((ObjectNode) api.requestExample()).deepCopy();
        facts.forEach((name, value) -> {
            Candidate c = byName.get(name);
            if (c != null && value != null) {
                JsonValues.set(body, c.path(), JsonValues.toNode(value));
            }
        });
        return body;
    }

    private static String kind(Object v) {
        return v == null ? "null" : v instanceof String ? "string" : v instanceof Boolean ? "bool"
                : v instanceof Double || v instanceof Float ? "decimal" : v instanceof Number ? "int"
                : v instanceof List<?> ? "list" : "object";
    }
}
