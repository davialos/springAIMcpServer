package com.springaimcpservercommon.celfaker.pipeline;

import com.springaimcpservercommon.celfaker.contract.ApiContract;
import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import com.springaimcpservercommon.celfaker.data.ApiData;
import com.springaimcpservercommon.celfaker.data.DataGenerator;
import com.springaimcpservercommon.celfaker.expr.CaseBuilder;
import com.springaimcpservercommon.celfaker.expr.CelCase;
import com.springaimcpservercommon.celfaker.expr.ExpressionFaker;
import com.springaimcpservercommon.celfaker.expr.GeneratedExpression;
import com.springaimcpservercommon.celfaker.k6.K6WorkflowGenerator;
import com.springaimcpservercommon.celfaker.payload.Candidate;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import com.springaimcpservercommon.celfaker.payload.PayloadAnalyzer;
import com.springaimcpservercommon.celfaker.values.AttributeValueMap;
import com.springaimcpservercommon.celfaker.values.AttributeValues;
import com.springaimcpservercommon.celfaker.values.ValueFactory;
import com.springaimcpservercommon.celfaker.workflow.Workflow;
import com.springaimcpservercommon.celfaker.workflow.WorkflowPlanner;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whole generation in one call: contract → parameters → value map → expressions → case matrix → API data →
 * k6 workflow suite. Pure and deterministic for a seed; the CLI writes the result to disk, the dashboard zips it.
 */
public final class FakerPipeline {

    /**
     * Generation request.
     *
     * @param contract           the user's APIs
     * @param workflow           the flow to run; {@code null} proposes one from the contract
     * @param valueMap           a (hand-edited) attribute map to reuse; {@code null} generates it; parameters it does not
     *                           list are generated
     * @param seed               seed
     * @param validCount         valid request bodies per API
     * @param expressionOptions  expression faker options
     * @param casesPerExpression input combinations evaluated per expression (0 = skip the case file)
     */
    public record Request(ApiContract contract, Workflow workflow, AttributeValueMap valueMap, long seed,
                          int validCount, ExpressionFaker.Options expressionOptions, int casesPerExpression) {

        /**
         * A request with defaults: seed 42, 30 valid bodies per API, default expressions, 12 cases each.
         *
         * @param contract the APIs
         * @return the request
         */
        public static Request of(ApiContract contract) {
            return new Request(contract, null, null, 42, 30, ExpressionFaker.Options.defaults(), 12);
        }
    }

    /**
     * Generation result.
     *
     * @param files    relative path to UTF-8 content: {@code parameters.json}, {@code attribute-map.json},
     *                 {@code expressions.json}, {@code cel-cases.json}, {@code summary.json} and {@code k6/**}
     * @param warnings things to review
     * @param summary  counts
     */
    public record Output(Map<String, String> files, List<String> warnings, Map<String, Object> summary) {
    }

    private FakerPipeline() {
    }

    /**
     * Runs the pipeline.
     *
     * @param request request
     * @return files, warnings and counts
     */
    public static Output run(Request request) {
        ApiContract contract = request.contract();
        List<String> warnings = new ArrayList<>();

        // 1. parameters per API, union into one library
        Map<String, List<Candidate>> perApi = new LinkedHashMap<>();
        Map<String, Candidate> union = new LinkedHashMap<>();
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        for (ApiSpec api : contract.apis()) {
            if (!api.hasBody()) {
                continue;
            }
            PayloadAnalyzer.Analysis analysis = PayloadAnalyzer.analyze(api.requestExample(), api.sysObject(), new HashSet<>(api.mapPaths()));
            analysis.skipped().forEach(s -> warnings.add(api.id() + ": " + s));
            List<Candidate> chosen = new ArrayList<>();
            Set<String> selected = new HashSet<>(api.selectedPaths());
            for (Candidate c : analysis.candidates()) {
                if (!selected.isEmpty() && !selected.contains(c.path())) {
                    continue;
                }
                Candidate known = union.get(c.celName());
                if (known != null && known.type() != c.type()) {
                    warnings.add(api.id() + ": " + c.celName() + " is " + c.type() + " here but " + known.type() + " elsewhere; dropped from this API's parameters");
                    continue;
                }
                union.putIfAbsent(c.celName(), c);
                bindings.computeIfAbsent(c.celName(), k -> new ArrayList<>()).add(api.id() + ":" + c.path());
                chosen.add(c);
            }
            perApi.put(api.id(), chosen);
        }
        List<Candidate> all = new ArrayList<>(union.values());
        ParameterLibrary library = FakerLibrary.library(all);

        // 2. attribute value map (generated, merged over a user-supplied one)
        ValueFactory factory = new ValueFactory(request.seed());
        AttributeValueMap generated = factory.build(all);
        Map<String, AttributeValues> merged = new LinkedHashMap<>(generated.attributes());
        if (request.valueMap() != null) {
            request.valueMap().attributes().forEach((name, v) -> {
                if (merged.containsKey(name)) {
                    merged.put(name, v);
                }
            });
        }
        AttributeValueMap valueMap = new AttributeValueMap(AttributeValueMap.VERSION, request.seed(), merged);

        // 3. expressions
        ExpressionFaker.Result expressions = new ExpressionFaker(library, valueMap, request.seed()).generate(request.expressionOptions());

        // 4. case matrix
        CaseBuilder caseBuilder = new CaseBuilder(library, valueMap, request.seed());
        ArrayNode caseDoc = JsonValues.MAPPER.createArrayNode();
        int caseCount = 0;
        if (request.casesPerExpression() > 0) {
            for (GeneratedExpression e : expressions.expressions()) {
                List<CelCase> cases = caseBuilder.build(e.expression(), request.casesPerExpression());
                ObjectNode n = caseDoc.addObject();
                n.put("expression", e.expression());
                n.put("category", e.category().name());
                n.put("description", e.description());
                n.set("cases", JsonValues.MAPPER.valueToTree(cases.stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("inputs", c.inputs());
                    m.put("expected", c.expected());
                    if (c.detail() != null) {
                        m.put("detail", c.detail());
                    }
                    return m;
                }).toList()));
                caseCount += cases.size();
            }
        }

        // 5. API data
        DataGenerator generator = new DataGenerator(valueMap, caseBuilder, request.seed());
        Map<String, ApiData> data = new LinkedHashMap<>();
        for (ApiSpec api : contract.apis()) {
            ApiData d = generator.generate(api, perApi.getOrDefault(api.id(), List.of()), request.validCount());
            d.warnings().forEach(w -> warnings.add(api.id() + ": " + w));
            data.put(api.id(), d);
        }

        // 6. workflow and k6
        Workflow workflow = request.workflow() != null ? request.workflow() : WorkflowPlanner.propose(contract);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("parameters.json", parameters(all, bindings));
        files.put("attribute-map.json", valueMap.toJson());
        files.put("expressions.json", pretty(JsonValues.MAPPER.valueToTree(expressions)));
        if (request.casesPerExpression() > 0) {
            files.put("cel-cases.json", pretty(caseDoc));
        }
        K6WorkflowGenerator.generate(contract, workflow, data).forEach((path, content) -> files.put("k6/" + path, content));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("parameters", all.size());
        summary.put("expressions", expressions.expressions().size());
        summary.put("rejectedExpressions", expressions.rejected().size());
        summary.put("celCases", caseCount);
        data.forEach((id, d) -> summary.put("data." + id, Map.of("valid", d.valid().size(), "invalid", d.invalid().size())));
        summary.put("warnings", warnings);
        files.put("summary.json", pretty(JsonValues.MAPPER.valueToTree(summary)));
        return new Output(files, warnings, summary);
    }

    private static String parameters(List<Candidate> all, Map<String, List<String>> bindings) {
        ArrayNode arr = JsonValues.MAPPER.createArrayNode();
        for (Candidate c : all) {
            ObjectNode n = arr.addObject();
            n.put("name", c.celName());
            n.put("sysObject", c.objectCode());
            n.put("sysObjectAttribute", c.attributeCode());
            n.put("dataType", c.type().name());
            n.set("sample", c.sample());
            n.set("boundTo", JsonValues.MAPPER.valueToTree(bindings.get(c.celName())));
        }
        return pretty(arr);
    }

    private static String pretty(tools.jackson.databind.JsonNode node) {
        return JsonValues.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }
}
