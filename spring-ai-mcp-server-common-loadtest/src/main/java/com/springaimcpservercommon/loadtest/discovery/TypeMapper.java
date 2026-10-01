package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.Constraints;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.springaimcpservercommon.loadtest.model.Schema;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps Java type trees of a project to request {@link Schema}s. DTO classes and records become named schemas
 * (registered once, referenced by {@link RefSchema}, so recursive types terminate); Jakarta Validation,
 * Jackson and OpenAPI annotations become constraints, names, examples and required flags.
 */
final class TypeMapper {

    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Collection", "Iterable", "ArrayList",
            "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "SortedSet", "Queue", "Deque", "Stream", "Flux");
    private static final Set<String> MAPS = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap",
            "MultiValueMap", "LinkedMultiValueMap", "Properties");
    private static final Set<String> WRAPPERS = Set.of("Optional", "ResponseEntity", "HttpEntity", "Mono",
            "CompletableFuture", "CompletionStage", "Callable", "DeferredResult", "Supplier");
    private static final Set<String> FREE_FORM = Set.of("Object", "JsonNode", "ObjectNode", "ArrayNode", "Void");

    private final SourceTrees trees;
    private final boolean snakeCaseJson;
    private final Map<String, ObjectSchema> schemas = new LinkedHashMap<>();
    private final Set<String> inProgress = new HashSet<>();

    TypeMapper(SourceTrees trees, boolean snakeCaseJson) {
        this.trees = trees;
        this.snakeCaseJson = snakeCaseJson;
    }

    /**
     * Named schemas registered so far.
     *
     * @return schemas by name
     */
    Map<String, ObjectSchema> schemas() {
        return schemas;
    }

    /**
     * Simple (raw) name of a type tree: {@code java.util.List<X>} → {@code List}.
     *
     * @param type type tree
     * @return simple name, or the tree's text for anything else
     */
    static String simpleName(Tree type) {
        return switch (type) {
            case ParameterizedTypeTree p -> simpleName(p.getType());
            case AnnotatedTypeTree a -> simpleName(a.getUnderlyingType());
            case IdentifierTree id -> id.getName().toString();
            case MemberSelectTree ms -> ms.getIdentifier().toString();
            case PrimitiveTypeTree pt -> pt.getPrimitiveTypeKind().name().toLowerCase(java.util.Locale.ROOT);
            case ArrayTypeTree arr -> simpleName(arr.getType()) + "[]";
            default -> type.toString();
        };
    }

    /**
     * Whether a type is a JSON scalar (maps to a {@link ScalarSchema} without consulting the sources).
     *
     * @param type type tree
     * @return {@code true} for primitives, boxes, strings, dates, UUIDs and project enums
     */
    boolean isScalar(Tree type) {
        Tree t = unwrapOptional(type);
        if (t instanceof PrimitiveTypeTree) {
            return true;
        }
        String name = simpleName(t);
        return builtinScalar(name) != null
                || trees.type(name).map(d -> d.tree().getKind() == Tree.Kind.ENUM).orElse(false);
    }

    private static Tree unwrapOptional(Tree type) {
        if (type instanceof ParameterizedTypeTree p && simpleName(p.getType()).equals("Optional")
                && !p.getTypeArguments().isEmpty()) {
            return p.getTypeArguments().getFirst();
        }
        return type;
    }

    /**
     * Schema of a type, constrained by the annotations on its declaration.
     *
     * @param type        type tree
     * @param annotations annotations of the field, record component or parameter
     * @return the schema
     */
    Schema map(Tree type, List<? extends AnnotationTree> annotations) {
        return constrain(map(type), annotations);
    }

    /**
     * Schema of a type.
     *
     * @param type type tree
     * @return the schema
     */
    Schema map(Tree type) {
        return switch (type) {
            case PrimitiveTypeTree p -> primitive(p);
            case AnnotatedTypeTree a -> constrain(map(a.getUnderlyingType()), a.getAnnotations());
            case ArrayTypeTree arr -> simpleName(arr.getType()).equals("byte")
                    ? ScalarSchema.of(ScalarType.STRING, "byte")
                    : new ArraySchema(map(arr.getType()), null, null);
            case WildcardTree w -> w.getBound() != null ? map(w.getBound()) : ObjectSchema.freeFormObject();
            case ParameterizedTypeTree p -> parameterized(p);
            case IdentifierTree id -> named(id.getName().toString());
            case MemberSelectTree ms -> named(ms.getIdentifier().toString());
            default -> ObjectSchema.freeFormObject();
        };
    }

    private Schema parameterized(ParameterizedTypeTree p) {
        String raw = simpleName(p.getType());
        List<? extends Tree> args = p.getTypeArguments();
        if (COLLECTIONS.contains(raw)) {
            return new ArraySchema(args.isEmpty() ? ObjectSchema.freeFormObject() : map(args.getFirst()), null, null);
        }
        if (MAPS.contains(raw)) {
            return ObjectSchema.freeFormObject();
        }
        if (WRAPPERS.contains(raw)) {
            return args.isEmpty() ? ObjectSchema.freeFormObject() : map(args.getFirst());
        }
        return named(raw); // generic DTO: type variables map to free-form values
    }

    private static Schema primitive(PrimitiveTypeTree p) {
        return switch (p.getPrimitiveTypeKind()) {
            case INT, SHORT, BYTE -> ScalarSchema.of(ScalarType.INTEGER, "int32");
            case LONG -> ScalarSchema.of(ScalarType.INTEGER, "int64");
            case FLOAT, DOUBLE -> ScalarSchema.of(ScalarType.NUMBER, "double");
            case BOOLEAN -> ScalarSchema.of(ScalarType.BOOLEAN, null);
            case CHAR -> ScalarSchema.of(ScalarType.STRING, null)
                    .withConstraints(new Constraints(1L, 1L, null, null, null, null));
            default -> ObjectSchema.freeFormObject();
        };
    }

    private static @Nullable ScalarSchema builtinScalar(String name) {
        return switch (name) {
            case "String", "CharSequence", "StringBuilder" -> ScalarSchema.of(ScalarType.STRING, null);
            case "Character" -> ScalarSchema.of(ScalarType.STRING, null)
                    .withConstraints(new Constraints(1L, 1L, null, null, null, null));
            case "Integer", "Short", "Byte", "AtomicInteger" -> ScalarSchema.of(ScalarType.INTEGER, "int32");
            case "Long", "BigInteger", "AtomicLong" -> ScalarSchema.of(ScalarType.INTEGER, "int64");
            case "Double", "Float", "BigDecimal", "Number" -> ScalarSchema.of(ScalarType.NUMBER, "double");
            case "Boolean" -> ScalarSchema.of(ScalarType.BOOLEAN, null);
            case "UUID" -> ScalarSchema.of(ScalarType.STRING, "uuid");
            case "LocalDate", "Date" -> ScalarSchema.of(ScalarType.STRING, "date");
            case "LocalDateTime", "OffsetDateTime", "ZonedDateTime", "Instant", "Timestamp", "Calendar" ->
                    ScalarSchema.of(ScalarType.STRING, "date-time");
            case "LocalTime", "OffsetTime" -> ScalarSchema.of(ScalarType.STRING, "time");
            case "Duration", "Period" -> ScalarSchema.of(ScalarType.STRING, "duration");
            case "YearMonth" -> ScalarSchema.of(ScalarType.STRING, "year-month");
            case "Year" -> ScalarSchema.of(ScalarType.INTEGER, "year");
            case "URI", "URL" -> ScalarSchema.of(ScalarType.STRING, "uri");
            case "Currency" -> ScalarSchema.of(ScalarType.STRING, "currency");
            case "Locale" -> ScalarSchema.of(ScalarType.STRING, "locale");
            case "ZoneId", "TimeZone" -> ScalarSchema.of(ScalarType.STRING, "timezone");
            case "InetAddress" -> ScalarSchema.of(ScalarType.STRING, "ipv4");
            default -> null;
        };
    }

    private Schema named(String name) {
        ScalarSchema scalar = builtinScalar(name);
        if (scalar != null) {
            return scalar;
        }
        if (FREE_FORM.contains(name) || MAPS.contains(name)) {
            return ObjectSchema.freeFormObject();
        }
        if (COLLECTIONS.contains(name)) {
            return new ArraySchema(ObjectSchema.freeFormObject(), null, null);
        }
        Optional<SourceTrees.TypeDecl> decl = trees.type(name);
        if (decl.isEmpty()) {
            return ObjectSchema.freeFormObject(); // a library type we cannot see
        }
        ClassTree ct = decl.get().tree();
        if (ct.getKind() == Tree.Kind.ENUM) {
            return new ScalarSchema(ScalarType.STRING, null, Constraints.NONE, enumConstants(ct), null);
        }
        if (ct.getKind() == Tree.Kind.INTERFACE || ct.getModifiers().getFlags().contains(Modifier.ABSTRACT)) {
            return ObjectSchema.freeFormObject(); // polymorphic: concrete subtype unknown
        }
        if (!schemas.containsKey(name) && inProgress.add(name)) {
            try {
                schemas.put(name, objectSchema(ct));
            } finally {
                inProgress.remove(name);
            }
        }
        return new RefSchema(name);
    }

    static List<String> enumConstants(ClassTree ct) {
        List<String> out = new ArrayList<>();
        for (Tree m : ct.getMembers()) {
            if (m instanceof VariableTree v && v.getInitializer() instanceof NewClassTree
                    && simpleName(v.getType()).equals(ct.getSimpleName().toString())) {
                out.add(v.getName().toString());
            }
        }
        return out;
    }

    private ObjectSchema objectSchema(ClassTree ct) {
        Map<String, Property> props = new LinkedHashMap<>();
        boolean snake = snakeCaseJson || SourceTrees.annotation(ct.getModifiers(), "JsonNaming")
                .map(a -> a.toString().contains("Snake")).orElse(false);
        for (VariableTree field : fields(ct, new HashSet<>())) {
            List<? extends AnnotationTree> anns = field.getModifiers().getAnnotations();
            if (SourceTrees.has(field.getModifiers(), "JsonIgnore", "Null", "Transient")
                    || readOnly(field) || field.getModifiers().getFlags().contains(Modifier.TRANSIENT)) {
                continue;
            }
            String javaName = field.getName().toString();
            String jsonName = SourceTrees.annotation(field.getModifiers(), "JsonProperty")
                    .flatMap(a -> trees.string(a, "value"))
                    .filter(s -> !s.isBlank())
                    .orElse(snake ? Names.snakeCase(javaName) : javaName);
            Schema schema = map(field.getType(), anns);
            props.put(jsonName, new Property(schema, required(field), sensitive(field, javaName), description(field)));
        }
        return new ObjectSchema(props, false);
    }

    /** Instance fields of a class and its parsed superclasses, superclass fields first. */
    private List<VariableTree> fields(ClassTree ct, Set<String> seen) {
        List<VariableTree> out = new ArrayList<>();
        if (!seen.add(ct.getSimpleName().toString())) {
            return out;
        }
        Tree ext = ct.getExtendsClause();
        if (ext != null) {
            trees.type(simpleName(ext)).ifPresent(sup -> out.addAll(fields(sup.tree(), seen)));
        }
        for (Tree m : ct.getMembers()) {
            if (m instanceof VariableTree v && !v.getModifiers().getFlags().contains(Modifier.STATIC)) {
                out.add(v);
            }
        }
        return out;
    }

    private boolean readOnly(VariableTree field) {
        return SourceTrees.annotation(field.getModifiers(), "JsonProperty", "Schema")
                .flatMap(a -> trees.string(a, "access", "accessMode"))
                .map(s -> s.equals("READ_ONLY"))
                .orElse(false);
    }

    boolean required(VariableTree v) {
        if (SourceTrees.has(v.getModifiers(), "NotNull", "NotBlank", "NotEmpty", "Nonnull")) {
            return true;
        }
        for (AnnotationTree a : v.getModifiers().getAnnotations()) {
            String n = SourceTrees.simpleName(a);
            if (n.equals("Schema") && (trees.string(a, "requiredMode").map("REQUIRED"::equals).orElse(false)
                    || trees.string(a, "required").map("true"::equals).orElse(false))) {
                return true;
            }
            if (n.equals("JsonProperty") && trees.string(a, "required").map("true"::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    boolean sensitive(VariableTree v, String javaName) {
        if (Names.isSensitive(javaName)) {
            return true;
        }
        return SourceTrees.annotation(v.getModifiers(), "AiEntityProperty", "AiParam")
                .flatMap(a -> trees.string(a, "classification"))
                .map(c -> c.equals("CONFIDENTIAL") || c.equals("RESTRICTED"))
                .orElse(false);
    }

    @Nullable String description(VariableTree v) {
        for (AnnotationTree a : v.getModifiers().getAnnotations()) {
            String n = SourceTrees.simpleName(a);
            if (n.equals("Schema") || n.equals("Parameter")) {
                Optional<String> d = trees.string(a, "description");
                if (d.isPresent()) {
                    return d.get();
                }
            }
            if (n.equals("AiEntityProperty") || n.equals("AiParam")) {
                Optional<String> d = trees.string(a, "meaning", "description", "value");
                if (d.isPresent()) {
                    return d.get();
                }
            }
        }
        return null;
    }

    /**
     * Applies validation and documentation annotations to a schema.
     *
     * @param schema      base schema
     * @param annotations annotations of the declaration
     * @return the constrained schema
     */
    Schema constrain(Schema schema, List<? extends AnnotationTree> annotations) {
        if (annotations.isEmpty()) {
            return schema;
        }
        if (schema instanceof ArraySchema arr) {
            Integer min = arr.minItems();
            Integer max = arr.maxItems();
            for (AnnotationTree a : annotations) {
                switch (SourceTrees.simpleName(a)) {
                    case "Size" -> {
                        min = trees.string(a, "min").map(Integer::valueOf).orElse(min);
                        max = trees.string(a, "max").map(Integer::valueOf).orElse(max);
                    }
                    case "NotEmpty" -> min = min == null || min < 1 ? Integer.valueOf(1) : min;
                    default -> { }
                }
            }
            return arr.withBounds(min, max);
        }
        if (!(schema instanceof ScalarSchema scalar)) {
            return schema;
        }
        Long minLength = null;
        Long maxLength = null;
        BigDecimal minimum = null;
        BigDecimal maximum = null;
        String pattern = null;
        Constraints.Temporal temporal = null;
        String format = scalar.format();
        String example = scalar.example();
        List<String> enumValues = scalar.enumValues();
        boolean integer = scalar.type() == ScalarType.INTEGER;
        for (AnnotationTree a : annotations) {
            switch (SourceTrees.simpleName(a)) {
                case "Size", "Length" -> {
                    minLength = trees.string(a, "min").map(Long::valueOf).orElse(minLength);
                    maxLength = trees.string(a, "max").map(Long::valueOf).orElse(maxLength);
                }
                case "NotBlank", "NotEmpty" -> minLength = minLength == null ? Long.valueOf(1) : minLength;
                case "Min", "DecimalMin" -> minimum = trees.string(a, "value").map(BigDecimal::new).orElse(minimum);
                case "Max", "DecimalMax" -> maximum = trees.string(a, "value").map(BigDecimal::new).orElse(maximum);
                case "Positive" -> minimum = integer ? BigDecimal.ONE : new BigDecimal("0.01");
                case "PositiveOrZero" -> minimum = BigDecimal.ZERO;
                case "Negative" -> maximum = integer ? BigDecimal.ONE.negate() : new BigDecimal("-0.01");
                case "NegativeOrZero" -> maximum = BigDecimal.ZERO;
                case "Digits" -> {
                    Optional<String> digits = trees.string(a, "integer");
                    if (digits.isPresent()) {
                        maximum = BigDecimal.TEN.pow(Integer.parseInt(digits.get())).subtract(BigDecimal.ONE);
                    }
                }
                case "Email" -> format = "email";
                case "Pattern" -> pattern = trees.string(a, "regexp").orElse(pattern);
                case "Past", "PastOrPresent" -> temporal = Constraints.Temporal.PAST;
                case "Future", "FutureOrPresent" -> temporal = Constraints.Temporal.FUTURE;
                case "URL" -> format = "uri";
                case "UUID" -> format = "uuid";
                case "Schema", "Parameter" -> {
                    example = trees.string(a, "example").orElse(example);
                    format = trees.string(a, "format").orElse(format);
                    pattern = trees.string(a, "pattern").orElse(pattern);
                    minimum = trees.string(a, "minimum").map(BigDecimal::new).orElse(minimum);
                    maximum = trees.string(a, "maximum").map(BigDecimal::new).orElse(maximum);
                    List<String> allowed = trees.strings(a, "allowableValues");
                    if (!allowed.isEmpty()) {
                        enumValues = allowed;
                    }
                }
                default -> { }
            }
        }
        Constraints overlay = new Constraints(minLength, maxLength, minimum, maximum, pattern, temporal);
        return new ScalarSchema(scalar.type(), format, scalar.constraints().overlay(overlay), enumValues, example);
    }
}
