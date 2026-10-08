package com.springaimcpservercommon.celfaker.expr;

import com.springaimcpservercommon.celfaker.values.AttributeValueMap;
import com.springaimcpservercommon.celfaker.values.AttributeValues;
import com.springaimcpservercommon.ruleengine.cel.CompiledExpression;
import com.springaimcpservercommon.ruleengine.cel.Facts;
import com.springaimcpservercommon.ruleengine.cel.FactException;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.cel.RuleCompilationException;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import dev.cel.runtime.CelEvaluationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Builds the input/result matrix of an expression: combinations of the referenced parameters' values (valid and
 * boundary), each evaluated by the real CEL runtime. The results are the expected outcomes load tests and rule
 * tests assert, and the source of data that satisfies (or violates) a rule.
 */
public final class CaseBuilder {

    private final ParameterLibrary library;
    private final AttributeValueMap values;
    private final long seed;

    /**
     * Creates a builder.
     *
     * @param library parameter library
     * @param values  value map
     * @param seed    seed for sampling large matrices
     */
    public CaseBuilder(ParameterLibrary library, AttributeValueMap values, long seed) {
        this.library = library;
        this.values = values;
        this.seed = seed;
    }

    /**
     * Builds the cases of one expression.
     *
     * @param expression CEL text (bool)
     * @param maxCases   upper bound on combinations; every value of every parameter appears at least once
     * @return the evaluated cases (empty when the expression does not compile)
     */
    public List<CelCase> build(String expression, int maxCases) {
        CompiledExpression compiled;
        try {
            compiled = library.compileBoolean(expression);
        } catch (RuleCompilationException e) {
            return List.of();
        }
        List<Parameter> params = compiled.referenced();
        List<List<Object>> domains = new ArrayList<>();
        for (Parameter p : params) {
            domains.add(values.find(p.celName()).map(AttributeValues::celInputs).orElse(List.of()).stream()
                    .filter(v -> coercible(p, v)).toList());
        }
        if (domains.stream().anyMatch(List::isEmpty)) {
            return List.of();
        }
        Random rnd = new Random(seed ^ expression.hashCode());
        List<CelCase> out = new ArrayList<>();
        for (int[] combo : combinations(domains, maxCases, rnd)) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            for (int i = 0; i < params.size(); i++) {
                inputs.put(params.get(i).celName(), domains.get(i).get(combo[i]));
            }
            out.add(evaluate(compiled, expression, inputs));
        }
        return out;
    }

    /**
     * Evaluates one input combination.
     *
     * @param compiled   compiled expression
     * @param expression its text
     * @param inputs     values per CEL name
     * @return the case with its result
     */
    public static CelCase evaluate(CompiledExpression compiled, String expression, Map<String, Object> inputs) {
        Facts facts = new Facts(inputs);
        Map<String, Object> bindings = new HashMap<>();
        try {
            for (Parameter p : compiled.referenced()) {
                bindings.put(p.celName(), facts.bind(p));
            }
            Object result = compiled.program().eval(bindings);
            return result instanceof Boolean b
                    ? new CelCase(expression, inputs, b.toString(), null)
                    : new CelCase(expression, inputs, "error", "result is not a bool");
        } catch (FactException e) {
            return new CelCase(expression, inputs, "error", e.getMessage());
        } catch (CelEvaluationException | RuntimeException e) {
            return new CelCase(expression, inputs, "error", String.valueOf(e.getMessage()));
        }
    }

    private static boolean coercible(Parameter p, Object v) {
        try {
            new Facts(Map.of(p.celName(), v)).bind(p);
            return true;
        } catch (FactException | RuntimeException e) {
            return false;
        }
    }

    private static List<int[]> combinations(List<List<Object>> domains, int max, Random rnd) {
        long total = 1;
        for (List<Object> d : domains) {
            total = Math.min(Long.MAX_VALUE / 2, total * d.size());
        }
        List<int[]> out = new ArrayList<>();
        if (total <= max) {
            int[] idx = new int[domains.size()];
            do {
                out.add(idx.clone());
            } while (next(idx, domains));
            return out;
        }
        int widest = domains.stream().mapToInt(List::size).max().orElse(1);
        Set<List<Integer>> seen = new LinkedHashSet<>();
        for (int i = 0; seen.size() < max && i < max * 10; i++) {
            int[] c = new int[domains.size()];
            for (int j = 0; j < c.length; j++) {
                c[j] = i < widest ? (i + j) % domains.get(j).size() : rnd.nextInt(domains.get(j).size());
            }
            List<Integer> key = new ArrayList<>();
            for (int v : c) {
                key.add(v);
            }
            if (seen.add(key)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean next(int[] idx, List<List<Object>> domains) {
        for (int i = idx.length - 1; i >= 0; i--) {
            if (++idx[i] < domains.get(i).size()) {
                return true;
            }
            idx[i] = 0;
        }
        return false;
    }
}
