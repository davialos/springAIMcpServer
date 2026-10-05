package com.springaimcpservercommon.ruleengine.cel;

import com.springaimcpservercommon.ruleengine.model.DataType;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelReference;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The parameter library as the CEL environment: every {@code object.attribute} is a typed CEL variable, so an
 * expression is type-checked when it is saved, not when a customer's transaction hits it.
 *
 * <p>Immutable once built; rebuilt by the cache when the library changes. Thread-safe.
 */
public final class ParameterLibrary {

    /** Longest accepted expression (matches the {@code ck_re_rule_expression} column check). */
    public static final int MAX_EXPRESSION_LENGTH = 8192;

    private static final CelOptions OPTIONS = CelOptions.current()
            .maxExpressionCodePointSize(MAX_EXPRESSION_LENGTH)
            .maxParseRecursionDepth(64)
            .comprehensionMaxIterations(10_000)
            .build();

    private final Map<String, Parameter> byName;
    private final Cel booleanCel;
    private final Cel stringCel;

    /**
     * Builds the CEL environments for the given parameters.
     *
     * @param parameters active parameters
     */
    public ParameterLibrary(Collection<Parameter> parameters) {
        Map<String, Parameter> map = new LinkedHashMap<>();
        for (Parameter p : parameters) {
            map.put(p.celName(), p);
        }
        this.byName = Map.copyOf(map);
        this.booleanCel = environment(parameters, SimpleType.BOOL);
        this.stringCel = environment(parameters, SimpleType.STRING);
    }

    private static Cel environment(Collection<Parameter> parameters, CelType resultType) {
        CelBuilder builder = CelFactory.standardCelBuilder().setOptions(OPTIONS).setResultType(resultType);
        for (Parameter p : parameters) {
            builder.addVar(p.celName(), celType(p.dataType()));
        }
        return builder.build();
    }

    /**
     * Maps a library data type to its CEL type.
     *
     * @param type library type
     * @return CEL type
     */
    public static CelType celType(DataType type) {
        return switch (type) {
            case STRING -> SimpleType.STRING;
            case INT -> SimpleType.INT;
            case DOUBLE -> SimpleType.DOUBLE;
            case BOOL -> SimpleType.BOOL;
            case TIMESTAMP -> SimpleType.TIMESTAMP;
            case DURATION -> SimpleType.DURATION;
            case LIST_STRING -> ListType.create(SimpleType.STRING);
            case LIST_INT -> ListType.create(SimpleType.INT);
            case LIST_DOUBLE -> ListType.create(SimpleType.DOUBLE);
            case MAP -> MapType.create(SimpleType.STRING, SimpleType.DYN);
            case ANY -> SimpleType.DYN;
        };
    }

    /**
     * Looks a parameter up by its CEL name.
     *
     * @param celName {@code object.attribute}
     * @return the parameter, if the library has it
     */
    public Optional<Parameter> find(String celName) {
        return Optional.ofNullable(byName.get(celName));
    }

    /**
     * All parameters (for UIs and expression editors).
     *
     * @return the parameters
     */
    public Collection<Parameter> parameters() {
        return byName.values();
    }

    /**
     * Compiles a rule expression; it must type-check to bool.
     *
     * @param expression CEL text
     * @return the compiled expression
     * @throws RuleCompilationException on a syntax error, an unknown parameter, a type error or a non-bool result
     */
    public CompiledExpression compileBoolean(String expression) throws RuleCompilationException {
        return compile(booleanCel, expression);
    }

    /**
     * Compiles a recipient expression; it must type-check to string.
     *
     * @param expression CEL text
     * @return the compiled expression
     * @throws RuleCompilationException on a syntax error, an unknown parameter, a type error or a non-string result
     */
    public CompiledExpression compileString(String expression) throws RuleCompilationException {
        return compile(stringCel, expression);
    }

    private CompiledExpression compile(Cel cel, String expression) throws RuleCompilationException {
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new RuleCompilationException("expression longer than " + MAX_EXPRESSION_LENGTH + " characters");
        }
        CelValidationResult result = cel.compile(expression);
        if (result.hasError()) {
            throw new RuleCompilationException(result.getErrorString());
        }
        try {
            CelAbstractSyntaxTree ast = result.getAst();
            List<Parameter> referenced = new ArrayList<>();
            for (CelReference reference : ast.getReferenceMap().values()) {
                Parameter p = byName.get(reference.name());
                if (p != null && !referenced.contains(p)) {
                    referenced.add(p);
                }
            }
            return new CompiledExpression(expression, cel.createProgram(ast), referenced);
        } catch (CelValidationException | CelEvaluationException e) {
            throw new RuleCompilationException(String.valueOf(e.getMessage()));
        }
    }
}
