package com.springaimcpservercommon.ruleengine.library;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google CEL for the rules. Every sys object of the cached {@link ParameterLibrary} is a CEL variable (a map of
 * attributes), so {@code customer.age >= 18} reads the way the library names things. Compiling checks the syntax,
 * that the expression yields a boolean, that every object exists and that every {@code object.attribute} is in the
 * library. Compiled programs are cached per library version, so a library change recompiles on next use.
 */
@Component
public class CelEngine {

    private static final int MAX_CACHED = 5_000;
    private static final CelOptions OPTIONS = CelOptions.current().enableHeterogeneousNumericComparisons(true).build();
    private static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(OPTIONS).build();

    /** A compiled rule expression. */
    public record Compiled(String expression, CelRuntime.Program program, Set<String> references) { }

    /** What is wrong with an expression. */
    public static final class ExpressionException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final transient List<String> problems;

        public ExpressionException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        /**
         * @return one line per problem
         */
        public List<String> problems() {
            return problems;
        }
    }

    private final ParameterLibraryService libraryService;
    private final Map<String, CelCompiler> compilers = new ConcurrentHashMap<>();
    private final Map<String, Compiled> programs = new ConcurrentHashMap<>();

    public CelEngine(ParameterLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    /**
     * Compiles (or takes from the cache) an expression against the current library.
     *
     * @param expression CEL text
     * @return the compiled expression
     * @throws ExpressionException when it does not compile, is not boolean or names unknown objects/attributes
     */
    public Compiled compile(String expression) {
        ParameterLibrary library = libraryService.current();
        String key = library.version() + "\u0000" + expression;
        Compiled cached = programs.get(key);
        if (cached != null) {
            return cached;
        }
        Compiled compiled = build(expression, library);
        if (programs.size() > MAX_CACHED) {
            programs.clear();
        }
        programs.put(key, compiled);
        return compiled;
    }

    /**
     * Evaluates a compiled expression.
     *
     * @param compiled  the expression
     * @param variables CEL variables: object code → attribute → typed value
     * @return the boolean result
     * @throws ExpressionException when evaluation fails (a missing attribute, a type mismatch) or is not boolean
     */
    public boolean evaluate(Compiled compiled, Map<String, Object> variables) {
        try {
            Object result = compiled.program().eval(variables);
            if (result instanceof Boolean b) {
                return b;
            }
            throw new ExpressionException(List.of("the expression returned " + result + ", not a boolean"));
        } catch (CelEvaluationException e) {
            throw new ExpressionException(List.of(e.getMessage()));
        }
    }

    private Compiled build(String expression, ParameterLibrary library) {
        CelCompiler compiler = compilers.computeIfAbsent(library.version(), v -> {
            if (compilers.size() > 4) {
                compilers.clear();
            }
            var builder = CelCompilerFactory.standardCelCompilerBuilder().setOptions(OPTIONS)
                    .setStandardMacros(CelStandardMacro.STANDARD_MACROS) // has, all, exists, exists_one, map, filter
                    .setResultType(SimpleType.BOOL);
            library.objects().keySet().forEach(code ->
                    builder.addVar(code, MapType.create(SimpleType.STRING, SimpleType.DYN)));
            return builder.build();
        });
        CelValidationResult result = compiler.compile(expression);
        if (result.hasError()) {
            throw new ExpressionException(List.of(result.getErrorString().strip().split("\n")));
        }
        CelAbstractSyntaxTree ast;
        try {
            ast = result.getAst();
        } catch (CelValidationException e) {
            throw new ExpressionException(List.of(e.getMessage()));
        }
        Set<String> references = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        CelNavigableAst.fromAst(ast).getRoot().allNodes().forEach(node -> inspect(node, library, references, problems));
        if (!problems.isEmpty()) {
            throw new ExpressionException(problems);
        }
        try {
            return new Compiled(expression, RUNTIME.createProgram(ast), references);
        } catch (CelEvaluationException e) {
            throw new ExpressionException(List.of(e.getMessage()));
        }
    }

    /** {@code object.attribute} selections must name an attribute of the library. */
    private static void inspect(CelNavigableExpr node, ParameterLibrary library, Set<String> references,
                                List<String> problems) {
        CelExpr expr = node.expr();
        if (expr.getKind() != CelExpr.ExprKind.Kind.SELECT) {
            return;
        }
        CelExpr operand = expr.select().operand();
        if (operand.getKind() != CelExpr.ExprKind.Kind.IDENT) {
            return;
        }
        String object = operand.ident().name();
        if (!library.objects().containsKey(object)) {
            return; // a comprehension variable or a nested field, not a library object
        }
        String attribute = expr.select().field();
        if (library.attribute(object, attribute).isEmpty()) {
            problems.add("'" + object + "." + attribute + "' is not in the parameter library");
        } else {
            references.add(object + "." + attribute);
        }
    }
}
