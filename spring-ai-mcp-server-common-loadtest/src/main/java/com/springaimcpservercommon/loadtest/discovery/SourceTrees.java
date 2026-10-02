package com.springaimcpservercommon.loadtest.discovery;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import org.jspecify.annotations.Nullable;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parsed Java sources of a project and the lookups the scanners need: types by simple name and compile-time
 * {@code String} constants. Parsing uses the JDK's own compiler tree API ({@code jdk.compiler}), without
 * attribution, so the project does not have to compile on this classpath and no parser dependency is needed.
 */
final class SourceTrees {

    /** A top-level or nested type declaration. */
    record TypeDecl(ClassTree tree, String packageName, @Nullable String outer) {
        String simpleName() {
            return tree.getSimpleName().toString();
        }
    }

    private final Map<String, TypeDecl> types = new LinkedHashMap<>();
    private final Map<String, ExpressionTree> constants = new HashMap<>();
    private final Map<String, List<String>> constantsBySimpleName = new HashMap<>();

    private SourceTrees() {
    }

    /**
     * Parses the given source files.
     *
     * @param files Java source files
     * @return the parsed trees
     * @throws IllegalStateException when no system Java compiler is available (running on a JRE)
     */
    static SourceTrees parse(List<Path> files) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Source discovery needs a JDK (no system Java compiler found)");
        }
        SourceTrees trees = new SourceTrees();
        if (files.isEmpty()) {
            return trees;
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> objects = fm.getJavaFileObjectsFromPaths(files);
            // Diagnostics are discarded: only the syntax tree is needed and it is produced even for files that
            // would not compile here (missing dependencies).
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { }, List.of("-proc:none"), null, objects);
            for (CompilationUnitTree unit : task.parse()) {
                String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                for (Tree decl : unit.getTypeDecls()) {
                    if (decl instanceof ClassTree ct) {
                        trees.index(ct, pkg, null);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return trees;
    }

    private void index(ClassTree ct, String pkg, @Nullable String outer) {
        String name = ct.getSimpleName().toString();
        if (name.isEmpty()) {
            return;
        }
        types.putIfAbsent(name, new TypeDecl(ct, pkg, outer));
        for (Tree member : ct.getMembers()) {
            if (member instanceof ClassTree nested) {
                index(nested, pkg, name);
            } else if (member instanceof VariableTree v && v.getInitializer() != null && isConstantHolder(ct, v)) {
                String key = name + "." + v.getName();
                constants.put(key, v.getInitializer());
                constantsBySimpleName.computeIfAbsent(v.getName().toString(), k -> new ArrayList<>()).add(key);
            }
        }
    }

    private static boolean isConstantHolder(ClassTree owner, VariableTree v) {
        Set<javax.lang.model.element.Modifier> flags = v.getModifiers().getFlags();
        boolean iface = owner.getKind() == Tree.Kind.INTERFACE;
        return iface || (flags.contains(javax.lang.model.element.Modifier.STATIC)
                && flags.contains(javax.lang.model.element.Modifier.FINAL));
    }

    /**
     * All type declarations, in parse order.
     *
     * @return types
     */
    Iterable<TypeDecl> types() {
        return types.values();
    }

    /**
     * A type by simple name.
     *
     * @param simpleName simple name
     * @return the declaration, if parsed
     */
    Optional<TypeDecl> type(String simpleName) {
        return Optional.ofNullable(types.get(simpleName));
    }

    // ── annotations ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Simple name of an annotation's type.
     *
     * @param a annotation
     * @return simple name, e.g. {@code GetMapping}
     */
    static String simpleName(AnnotationTree a) {
        String s = a.getAnnotationType().toString();
        return s.substring(s.lastIndexOf('.') + 1);
    }

    /**
     * Finds an annotation by simple name.
     *
     * @param mods  modifiers
     * @param names accepted simple names
     * @return the first match
     */
    static Optional<AnnotationTree> annotation(ModifiersTree mods, String... names) {
        for (AnnotationTree a : mods.getAnnotations()) {
            String n = simpleName(a);
            for (String name : names) {
                if (n.equals(name)) {
                    return Optional.of(a);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether an annotation with one of the simple names is present.
     *
     * @param mods  modifiers
     * @param names accepted simple names
     * @return {@code true} if present
     */
    static boolean has(ModifiersTree mods, String... names) {
        return annotation(mods, names).isPresent();
    }

    /**
     * Raw expression of an annotation attribute; {@code value} also matches the single unnamed argument.
     *
     * @param a         annotation
     * @param attribute attribute name
     * @return the expression, if set
     */
    static Optional<ExpressionTree> attribute(AnnotationTree a, String attribute) {
        for (ExpressionTree arg : a.getArguments()) {
            if (arg instanceof AssignmentTree as) {
                if (as.getVariable().toString().equals(attribute)) {
                    return Optional.of(as.getExpression());
                }
            } else if (attribute.equals("value")) {
                return Optional.of(arg);
            }
        }
        return Optional.empty();
    }

    /**
     * Values of an annotation attribute as strings: literals and constants are evaluated, arrays flattened,
     * enum constants reduced to their simple name.
     *
     * @param a          annotation
     * @param attributes attribute names to try in order (e.g. {@code value}, {@code path})
     * @return values of the first attribute that is set, or an empty list
     */
    List<String> strings(AnnotationTree a, String... attributes) {
        for (String attribute : attributes) {
            Optional<ExpressionTree> e = attribute(a, attribute);
            if (e.isPresent()) {
                List<String> out = new ArrayList<>();
                collect(e.get(), out);
                return out;
            }
        }
        return List.of();
    }

    /**
     * First value of an annotation attribute.
     *
     * @param a          annotation
     * @param attributes attribute names to try in order
     * @return the value, if set
     */
    Optional<String> string(AnnotationTree a, String... attributes) {
        List<String> values = strings(a, attributes);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    /**
     * Value of a compile-time constant string expression (literal, concatenation, {@code static final} field).
     *
     * @param e expression
     * @return the value, if the expression is a constant
     */
    Optional<String> constant(ExpressionTree e) {
        if (!(e instanceof LiteralTree) && !(e instanceof BinaryTree) && !(e instanceof MemberSelectTree)
                && !(e instanceof IdentifierTree) && !(e instanceof ParenthesizedTree)) {
            return Optional.empty();
        }
        if (e instanceof IdentifierTree id && constantsBySimpleName.getOrDefault(id.getName().toString(), List.of())
                .size() != 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(evaluate(e, new HashSet<>()));
    }

    private void collect(ExpressionTree e, List<String> out) {
        if (e instanceof NewArrayTree arr) {
            if (arr.getInitializers() != null) {
                for (ExpressionTree item : arr.getInitializers()) {
                    collect(item, out);
                }
            }
            return;
        }
        String v = evaluate(e, new HashSet<>());
        if (v != null) {
            out.add(v);
        }
    }

    /**
     * Evaluates a compile-time constant expression to a string: literals, string concatenation and references
     * to {@code static final} fields in the parsed sources. An unresolvable reference evaluates to its simple
     * name, which is the right answer for enum constants ({@code RequestMethod.GET}).
     */
    private @Nullable String evaluate(ExpressionTree e, Set<String> visiting) {
        return switch (e) {
            case LiteralTree lit -> lit.getValue() == null ? null : String.valueOf(lit.getValue());
            case ParenthesizedTree p -> evaluate(p.getExpression(), visiting);
            case BinaryTree bin when bin.getKind() == Tree.Kind.PLUS -> {
                String l = evaluate(bin.getLeftOperand(), visiting);
                String r = evaluate(bin.getRightOperand(), visiting);
                yield l == null || r == null ? null : l + r;
            }
            case MemberSelectTree ms -> {
                String owner = ms.getExpression().toString();
                String key = owner.substring(owner.lastIndexOf('.') + 1) + "." + ms.getIdentifier();
                yield resolveConstant(key, ms.getIdentifier().toString(), visiting);
            }
            case IdentifierTree id -> {
                List<String> keys = constantsBySimpleName.getOrDefault(id.getName().toString(), List.of());
                yield keys.size() == 1
                        ? resolveConstant(keys.getFirst(), id.getName().toString(), visiting)
                        : id.getName().toString();
            }
            default -> null;
        };
    }

    private @Nullable String resolveConstant(String key, String fallback, Set<String> visiting) {
        ExpressionTree init = constants.get(key);
        if (init == null || !visiting.add(key)) {
            return fallback;
        }
        try {
            String v = evaluate(init, visiting);
            return v != null ? v : fallback;
        } finally {
            visiting.remove(key);
        }
    }
}
