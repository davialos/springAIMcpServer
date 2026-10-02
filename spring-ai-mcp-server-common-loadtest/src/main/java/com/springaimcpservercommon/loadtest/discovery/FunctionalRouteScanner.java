package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Spring WebMvc.fn endpoints: {@code RouterFunction} beans built with {@code route().GET("/x", h)},
 * {@code RouterFunctions.route(RequestPredicates.GET("/x"), h)}, {@code .path("/p", b -> …)} and
 * {@code .nest(path("/p"), …)}. Request bodies are unknown (handlers read them imperatively), so writes get a
 * free-form JSON body.
 */
final class FunctionalRouteScanner {

    private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
    private static final Pattern PATH_VAR = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

    private final SourceTrees trees;
    private final ProjectSettings settings;

    FunctionalRouteScanner(SourceTrees trees, ProjectSettings settings) {
        this.trees = trees;
        this.settings = settings;
    }

    List<ApiEndpoint> scan() {
        List<ApiEndpoint> out = new ArrayList<>();
        for (SourceTrees.TypeDecl d : trees.types()) {
            for (Tree member : d.tree().getMembers()) {
                if (member instanceof MethodTree m && m.getReturnType() != null && m.getBody() != null
                        && TypeMapper.simpleName(m.getReturnType()).equals("RouterFunction")) {
                    new Visitor(d.simpleName(), out).scan(m.getBody(), "");
                }
            }
        }
        return out;
    }

    private final class Visitor extends TreeScanner<Void, String> {
        private final String owner;
        private final List<ApiEndpoint> out;

        Visitor(String owner, List<ApiEndpoint> out) {
            this.owner = owner;
            this.out = out;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, String prefix) {
            String name = methodName(node);
            List<? extends ExpressionTree> args = node.getArguments();
            Optional<String> first = args.isEmpty() ? Optional.empty() : trees.constant(args.getFirst());
            if (VERBS.contains(name) && first.isPresent()) {
                record(HttpMethod.parse(name), prefix + first.get());
            }
            if (name.equals("path") && args.size() == 2 && first.isPresent()) {
                scan(node.getMethodSelect(), prefix);
                scan(args.get(1), prefix + first.get()); // builder.path("/p", b -> b.GET(...))
                return null;
            }
            if (name.equals("nest") && args.size() == 2 && args.getFirst() instanceof MethodInvocationTree pred
                    && methodName(pred).equals("path") && !pred.getArguments().isEmpty()) {
                Optional<String> nested = trees.constant(pred.getArguments().getFirst());
                if (nested.isPresent()) {
                    scan(node.getMethodSelect(), prefix);
                    scan(args.get(1), prefix + nested.get()); // nest(path("/p"), () -> route()...)
                    return null;
                }
            }
            return super.visitMethodInvocation(node, prefix);
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree node, String prefix) {
            return scan(node.getBody(), prefix);
        }

        private void record(HttpMethod method, String rawPath) {
            String path = SpringSourceScanner.joinPath("", settings.resolvePlaceholders(rawPath));
            List<ApiParam> params = new ArrayList<>();
            Matcher m = PATH_VAR.matcher(path);
            while (m.find()) {
                params.add(new ApiParam(m.group(1), ParamLocation.PATH, true,
                        ScalarSchema.of(ScalarType.STRING, null), null));
            }
            String clean = PATH_VAR.matcher(path).replaceAll(r -> Matcher.quoteReplacement("{" + r.group(1) + "}"));
            StringBuilder id = new StringBuilder(method.name().toLowerCase(Locale.ROOT));
            for (String seg : clean.split("/")) {
                if (!seg.isEmpty() && !seg.startsWith("{") && !seg.equalsIgnoreCase("api")) {
                    id.append(' ').append(seg);
                }
            }
            out.add(new ApiEndpoint(Names.jsIdentifier(id.toString()), method, clean, null, List.of(owner), params,
                    method.hasBody() ? ObjectSchema.freeFormObject() : null, null, Set.of("source")));
        }
    }

    private static String methodName(MethodInvocationTree node) {
        ExpressionTree sel = node.getMethodSelect();
        return sel instanceof MemberSelectTree ms ? ms.getIdentifier().toString() : sel.toString();
    }
}
