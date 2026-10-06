package com.springaimcpservercommon.ruleengine.admin;

import com.springaimcpservercommon.ruleengine.cel.CompiledExpression;
import com.springaimcpservercommon.ruleengine.cel.FactException;
import com.springaimcpservercommon.ruleengine.cel.Facts;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.cel.RuleCompilationException;
import com.springaimcpservercommon.ruleengine.model.Outcome;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import dev.cel.runtime.CelEvaluationException;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The expression editor's helper: checks a CEL expression against the parameter library as the author types, and runs
 * it against sample values (a rule test bench) without saving anything. Never stores or logs the sample values.
 */
public final class ExpressionTester {

    /**
     * Result of {@link #validate(String)}.
     *
     * @param valid      whether the expression compiles to a boolean
     * @param errors     compiler issues with positions (empty when valid)
     * @param parameters CEL names of the library parameters the expression reads
     */
    public record Validation(boolean valid, List<String> errors, List<String> parameters) {
    }

    /**
     * Result of {@link #test(String, Map)}.
     *
     * @param outcome   TRUE, FALSE or ERROR
     * @param errorCode COMPILE_ERROR, MISSING_PARAMETER, INVALID_PARAMETER, NOT_BOOLEAN or EVALUATION_ERROR
     * @param detail    names the problem (a parameter or compiler issue), never a value
     */
    public record TestResult(Outcome outcome, @Nullable String errorCode, @Nullable String detail) {
    }

    private final Supplier<ParameterLibrary> library;

    /**
     * Creates the tester.
     *
     * @param library supplies the current parameter library (refreshed by the cache)
     */
    public ExpressionTester(Supplier<ParameterLibrary> library) {
        this.library = Objects.requireNonNull(library, "library");
    }

    /**
     * Compiles an expression.
     *
     * @param expression CEL text
     * @return validity, issues and the parameters read
     */
    public Validation validate(String expression) {
        try {
            CompiledExpression compiled = library.get().compileBoolean(expression);
            return new Validation(true, List.of(),
                    compiled.referenced().stream().map(Parameter::celName).toList());
        } catch (RuleCompilationException e) {
            return new Validation(false, List.of(e.getMessage()), List.of());
        }
    }

    /**
     * Runs an expression against sample values.
     *
     * @param expression CEL text
     * @param facts      sample values, flat or nested like a real request
     * @return the outcome
     */
    public TestResult test(String expression, Map<String, Object> facts) {
        CompiledExpression compiled;
        try {
            compiled = library.get().compileBoolean(expression);
        } catch (RuleCompilationException e) {
            return new TestResult(Outcome.ERROR, "COMPILE_ERROR", e.getMessage());
        }
        Facts bound = new Facts(facts);
        Map<String, Object> bindings = new HashMap<>();
        try {
            for (Parameter p : compiled.referenced()) {
                bindings.put(p.celName(), bound.bind(p));
            }
            Object value = compiled.program().eval(bindings);
            if (value instanceof Boolean b) {
                return new TestResult(b ? Outcome.TRUE : Outcome.FALSE, null, null);
            }
            return new TestResult(Outcome.ERROR, "NOT_BOOLEAN", "expression did not return a boolean");
        } catch (FactException e) {
            return new TestResult(Outcome.ERROR, e.code(), e.getMessage());
        } catch (CelEvaluationException | RuntimeException e) {
            return new TestResult(Outcome.ERROR, "EVALUATION_ERROR", "CEL evaluation failed");
        }
    }
}
