package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import javax.lang.model.element.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps the return type of a handler method to a response-validation schema ({@link ResponseSchemas}). The result is
 * what Jackson would write for the type, kept lenient on purpose: a member is only {@code required} when it is a Java
 * primitive (always serialised) on a class whose serialisation the source does not customise; dates accept every
 * representation Jackson can produce; relations of JPA entities, polymorphic and unparsed types are "anything".
 * Wrappers are looked through ({@code ResponseEntity<Optional<List<X>>>}), {@code Page}/{@code Slice} become
 * {@code {content: [...]}}, and generic DTOs ({@code ApiResponse<Customer>}) bind their type arguments.
 */
final class ResponseTypeMapper {

    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Collection", "Iterable", "ArrayList",
            "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "SortedSet", "Queue", "Deque", "Stream", "Flux");
    private static final Set<String> MAPS = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap",
            "MultiValueMap", "LinkedMultiValueMap", "Properties");
    private static final Set<String> WRAPPERS = Set.of("ResponseEntity", "HttpEntity", "Mono", "CompletableFuture",
            "CompletionStage", "Callable", "DeferredResult", "Supplier", "WebAsyncTask");
    private static final Set<String> PAGES = Set.of("Page", "Slice", "PageImpl", "SliceImpl", "Window", "PagedModel");
    /** Return types that are not a JSON document at all (or that nothing can be said about). */
    private static final Set<String> OPAQUE = Set.of("void", "Void", "Object", "String", "CharSequence", "byte[]",
            "Resource", "InputStream", "InputStreamResource", "StreamingResponseBody", "SseEmitter", "ModelAndView",
            "View", "RedirectView", "ResponseBodyEmitter", "File", "Path", "ServerSentEvent");
    private static final Set<String> FREE_FORM = Set.of("Object", "JsonNode", "ObjectNode", "ArrayNode", "Void",
            "Serializable");
    private static final Set<String> RELATIONS = Set.of("OneToMany", "ManyToMany", "ManyToOne", "OneToOne",
            "ElementCollection", "Embedded");
    /** Annotations that make a class's JSON differ from its fields. */
    private static final Set<String> CUSTOM = Set.of("JsonProperty", "JsonGetter", "JsonAlias", "JsonValue",
            "JsonSerialize", "JsonAnyGetter", "JsonView", "JsonFilter", "JsonUnwrapped", "JsonRawValue");

    private final SourceTrees trees;
    private final TypeMapper types;
    private final boolean snakeCaseJson;

    ResponseTypeMapper(SourceTrees trees, TypeMapper types, boolean snakeCaseJson) {
        this.trees = trees;
        this.types = types;
        this.snakeCaseJson = snakeCaseJson;
    }

    /**
     * Schema of a handler's return type.
     *
     * @param returnType the method's return type tree
     * @param bindings   type variables of the controller hierarchy bound to concrete types
     * @return the schema, or {@code null} when the response is not JSON or nothing useful can be said
     */
    @Nullable ObjectNode map(@Nullable Tree returnType, Map<String, Tree> bindings) {
        if (returnType == null) {
            return null;
        }
        Tree t = unwrap(returnType, bindings);
        if (t == null || OPAQUE.contains(TypeMapper.simpleName(t))) {
            return null;
        }
        ObjectNode n = node(t, bindings, 0, new HashSet<>());
        return ResponseSchemas.isEmpty(n) ? null : n;
    }

    /** Looks through {@code ResponseEntity<…>}, {@code Mono<…>}, {@code CompletableFuture<…>} and annotated types. */
    private static @Nullable Tree unwrap(Tree type, Map<String, Tree> bindings) {
        Tree t = type;
        for (int i = 0; i < 6; i++) {
            if (t instanceof AnnotatedTypeTree a) {
                t = a.getUnderlyingType();
            } else if (t instanceof ParameterizedTypeTree p && WRAPPERS.contains(TypeMapper.simpleName(p.getType()))) {
                if (p.getTypeArguments().isEmpty()) {
                    return null;
                }
                t = p.getTypeArguments().getFirst();
            } else if (t instanceof IdentifierTree id && bindings.containsKey(id.getName().toString())) {
                t = bindings.get(id.getName().toString());
            } else if (t instanceof IdentifierTree id && WRAPPERS.contains(id.getName().toString())) {
                return null; // raw ResponseEntity
            } else if (t instanceof WildcardTree w) {
                t = w.getBound();
                if (t == null) {
                    return null;
                }
            } else {
                break;
            }
        }
        return t;
    }

    private ObjectNode node(Tree type, Map<String, Tree> bindings, int depth, Set<String> visiting) {
        if (depth >= ResponseSchemas.MAX_DEPTH) {
            return ResponseSchemas.any();
        }
        return switch (type) {
            case PrimitiveTypeTree p -> switch (p.getPrimitiveTypeKind()) {
                case INT, SHORT, BYTE, LONG -> ResponseSchemas.scalar("integer");
                case FLOAT, DOUBLE -> ResponseSchemas.scalar("number");
                case BOOLEAN -> ResponseSchemas.scalar("boolean");
                case CHAR -> ResponseSchemas.scalar("string");
                default -> ResponseSchemas.any();
            };
            case AnnotatedTypeTree a -> node(a.getUnderlyingType(), bindings, depth, visiting);
            case ArrayTypeTree arr -> TypeMapper.simpleName(arr.getType()).equals("byte")
                    ? ResponseSchemas.scalar("string")
                    : ResponseSchemas.array(node(arr.getType(), bindings, depth + 1, visiting));
            case WildcardTree w -> w.getBound() != null ? node(w.getBound(), bindings, depth, visiting)
                    : ResponseSchemas.any();
            case ParameterizedTypeTree p -> parameterized(p, bindings, depth, visiting);
            case IdentifierTree id -> bindings.containsKey(id.getName().toString())
                    ? node(bindings.get(id.getName().toString()), Map.of(), depth + 1, visiting)
                    : named(id.getName().toString(), List.of(), bindings, depth, visiting);
            case MemberSelectTree ms -> named(ms.getIdentifier().toString(), List.of(), bindings, depth, visiting);
            default -> ResponseSchemas.any();
        };
    }

    private ObjectNode parameterized(ParameterizedTypeTree p, Map<String, Tree> bindings, int depth,
                                     Set<String> visiting) {
        String raw = TypeMapper.simpleName(p.getType());
        List<? extends Tree> args = p.getTypeArguments();
        if (raw.equals("Optional")) {
            ObjectNode inner = args.isEmpty() ? ResponseSchemas.any()
                    : node(args.getFirst(), bindings, depth, visiting);
            inner.put("nullable", true);
            return inner;
        }
        if (WRAPPERS.contains(raw)) {
            return args.isEmpty() ? ResponseSchemas.any() : node(args.getFirst(), bindings, depth, visiting);
        }
        if (COLLECTIONS.contains(raw)) {
            return ResponseSchemas.array(args.isEmpty() ? ResponseSchemas.any()
                    : node(args.getFirst(), bindings, depth + 1, visiting));
        }
        if (PAGES.contains(raw)) {
            return page(args.isEmpty() ? ResponseSchemas.any() : node(args.getFirst(), bindings, depth + 2, visiting));
        }
        if (MAPS.contains(raw)) {
            return ResponseSchemas.object();
        }
        return named(raw, args, bindings, depth, visiting);
    }

    private static ObjectNode page(ObjectNode element) {
        ObjectNode page = ResponseSchemas.object();
        ResponseSchemas.property(page, "content", ResponseSchemas.array(element), false);
        return page;
    }

    private ObjectNode named(String name, List<? extends Tree> args, Map<String, Tree> bindings, int depth,
                             Set<String> visiting) {
        ScalarSchema scalar = TypeMapper.builtinScalar(name);
        if (scalar != null) {
            return scalar(name, scalar);
        }
        if (FREE_FORM.contains(name)) {
            return ResponseSchemas.any();
        }
        if (MAPS.contains(name)) {
            return ResponseSchemas.object();
        }
        if (COLLECTIONS.contains(name)) {
            return ResponseSchemas.array(ResponseSchemas.any());
        }
        if (PAGES.contains(name)) {
            return page(ResponseSchemas.any());
        }
        Optional<SourceTrees.TypeDecl> decl = trees.type(name);
        if (decl.isEmpty()) {
            return ResponseSchemas.any(); // a library type, or a type variable we cannot resolve
        }
        ClassTree ct = decl.get().tree();
        if (ct.getKind() == Tree.Kind.ENUM) {
            return enumeration(ct);
        }
        if (ct.getKind() == Tree.Kind.INTERFACE || ct.getModifiers().getFlags().contains(Modifier.ABSTRACT)
                || !visiting.add(name)) {
            return ResponseSchemas.any(); // polymorphic or recursive: concrete shape unknown
        }
        try {
            return object(ct, bind(ct, args, bindings), depth, visiting);
        } finally {
            visiting.remove(name);
        }
    }

    /** Type variables of a generic DTO bound to the type arguments, resolved in the caller's scope. */
    private static Map<String, Tree> bind(ClassTree ct, List<? extends Tree> args, Map<String, Tree> outer) {
        Map<String, Tree> out = new LinkedHashMap<>();
        for (int i = 0; i < ct.getTypeParameters().size() && i < args.size(); i++) {
            Tree arg = args.get(i);
            Tree resolved = arg instanceof IdentifierTree id && outer.containsKey(id.getName().toString())
                    ? outer.get(id.getName().toString()) : arg;
            out.put(ct.getTypeParameters().get(i).getName().toString(), resolved);
        }
        return out;
    }

    private static ObjectNode scalar(String name, ScalarSchema s) {
        if (s.type() == ScalarType.BOOLEAN) {
            return ResponseSchemas.scalar("boolean");
        }
        String format = s.format() == null ? "" : s.format();
        if (List.of("date", "date-time", "time", "duration", "year-month").contains(format)) {
            // timestamps, ISO strings or [y,m,d] arrays, depending on the project's Jackson settings
            return ResponseSchemas.scalar("string", "number", "array");
        }
        return switch (s.type()) {
            case INTEGER -> name.equals("BigInteger") ? ResponseSchemas.scalar("integer", "string")
                    : ResponseSchemas.scalar("integer");
            case NUMBER -> name.equals("BigDecimal") || name.equals("Number") ? ResponseSchemas.scalar("number", "string")
                    : ResponseSchemas.scalar("number");
            default -> ResponseSchemas.scalar("string");
        };
    }

    private ObjectNode enumeration(ClassTree ct) {
        boolean renamed = SourceTrees.has(ct.getModifiers(), "JsonFormat") || ct.getMembers().stream()
                .anyMatch(m -> m instanceof VariableTree v && SourceTrees.has(v.getModifiers(), "JsonProperty", "JsonValue")
                        || m instanceof MethodTree mt && SourceTrees.has(mt.getModifiers(), "JsonValue"));
        List<String> constants = TypeMapper.enumConstants(ct);
        if (renamed || constants.isEmpty()) {
            return ResponseSchemas.scalar("string", "integer"); // wire values differ from the constant names
        }
        ObjectNode n = ResponseSchemas.scalar("string");
        var values = n.putArray("enum");
        constants.forEach(values::add);
        return n;
    }

    private ObjectNode object(ClassTree ct, Map<String, Tree> bindings, int depth, Set<String> visiting) {
        if (SourceTrees.has(ct.getModifiers(), "JsonSerialize", "JsonValue", "JsonFilter")) {
            return ResponseSchemas.any();
        }
        boolean entity = TypeMapper.isJpa(ct);
        boolean snake = snakeCaseJson || SourceTrees.annotation(ct.getModifiers(), "JsonNaming")
                .map(a -> a.toString().contains("Snake")).orElse(false);
        boolean customised = customised(ct) || SourceTrees.annotation(ct.getModifiers(), "JsonInclude")
                .map(a -> a.toString().contains("NON_DEFAULT") || a.toString().contains("NON_EMPTY")).orElse(false);
        ObjectNode out = ResponseSchemas.object();
        for (VariableTree field : types.fields(ct, new HashSet<>())) {
            ModifiersTree mods = field.getModifiers();
            if (SourceTrees.has(mods, "JsonIgnore", "JsonUnwrapped", "JsonAnyGetter")
                    || mods.getFlags().contains(Modifier.TRANSIENT) || writeOnly(field)) {
                continue;
            }
            String javaName = field.getName().toString();
            String json = SourceTrees.annotation(mods, "JsonProperty").flatMap(a -> trees.string(a, "value"))
                    .filter(v -> !v.isBlank()).orElse(snake ? Names.snakeCase(javaName) : javaName);
            ObjectNode schema;
            if (SourceTrees.has(mods, "JsonSerialize", "JsonRawValue", "JsonFormat") || entity
                    && SourceTrees.has(mods, RELATIONS.toArray(String[]::new))) {
                schema = ResponseSchemas.any();
            } else {
                schema = node(field.getType(), bindings, depth + 1, visiting);
            }
            boolean primitive = field.getType() instanceof PrimitiveTypeTree;
            ResponseSchemas.property(out, json, schema, primitive && !customised);
        }
        return out;
    }

    /** Whether the class has Jackson customisation the field list cannot see (getters renamed, views, filters…). */
    private boolean customised(ClassTree ct) {
        for (Tree m : ct.getMembers()) {
            if (m instanceof MethodTree mt && SourceTrees.has(mt.getModifiers(), CUSTOM.toArray(String[]::new))
                    || m instanceof VariableTree v
                    && SourceTrees.has(v.getModifiers(), "JsonView", "JsonUnwrapped", "JsonAnyGetter")) {
                return true;
            }
        }
        Tree ext = ct.getExtendsClause();
        return ext != null && trees.type(TypeMapper.simpleName(ext)).map(d -> customised(d.tree())).orElse(false);
    }

    private boolean writeOnly(VariableTree field) {
        return SourceTrees.annotation(field.getModifiers(), "JsonProperty")
                .flatMap(a -> trees.string(a, "access"))
                .map(s -> s.equals("WRITE_ONLY")).orElse(false);
    }
}
