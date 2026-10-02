package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.Constraints;
import com.springaimcpservercommon.loadtest.model.EntityTable;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.RefSchema;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.springaimcpservercommon.loadtest.model.Schema;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers a Spring MVC project's REST operations from its Java sources: {@code @RestController}/{@code @Controller}
 * classes and API interfaces, their {@code @*Mapping} methods, parameters ({@code @PathVariable},
 * {@code @RequestParam}, {@code @RequestHeader}, {@code @RequestBody}, {@code Pageable}, query objects) and the
 * request DTOs with their validation constraints. Also returns the JPA entities found.
 */
public final class SpringSourceScanner {

    private static final Map<String, HttpMethod> SHORTCUTS = Map.of(
            "GetMapping", HttpMethod.GET, "PostMapping", HttpMethod.POST, "PutMapping", HttpMethod.PUT,
            "PatchMapping", HttpMethod.PATCH, "DeleteMapping", HttpMethod.DELETE);

    /** Handler-method parameter types Spring resolves from the request context, not from the client. */
    private static final Set<String> FRAMEWORK_TYPES = Set.of("HttpServletRequest", "HttpServletResponse",
            "ServletRequest", "ServletResponse", "WebRequest", "NativeWebRequest", "HttpSession", "Principal",
            "Authentication", "Model", "ModelMap", "BindingResult", "Errors", "Locale", "TimeZone", "ZoneId",
            "UriComponentsBuilder", "HttpHeaders", "InputStream", "OutputStream", "Reader", "Writer", "HttpMethod",
            "SessionStatus", "RedirectAttributes", "ServerHttpRequest", "ServerHttpResponse", "ServerWebExchange",
            "JwtAuthenticationToken", "Jwt", "OidcUser", "OAuth2User", "UserDetails", "SecurityContext",
            "SseEmitter", "ResponseBodyEmitter", "StreamingResponseBody");
    private static final Set<String> CONTEXT_ANNOTATIONS = Set.of("AuthenticationPrincipal", "RequestAttribute",
            "SessionAttribute", "CookieValue", "CurrentSecurityContext", "MatrixVariable", "Value");
    private static final Set<String> GENERIC_NAMES = Set.of("get", "list", "all", "create", "update", "delete",
            "remove", "patch", "save", "search", "find", "findAll", "findOne", "findById", "getById", "getAll",
            "index", "show", "add", "edit", "replace", "upsert", "query", "count", "handle");
    private static final Pattern PATH_VAR = Pattern.compile("\\{([^}:]+)(?::([^}]*))?}");

    private final Consumer<String> log;

    /**
     * Creates a scanner.
     *
     * @param log receives human-readable notes about skipped or approximated operations
     */
    public SpringSourceScanner(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Scans a project directory.
     *
     * @param projectDir project root (single module or multi-module build)
     * @return the discovered catalog (endpoints, request schemas, entities)
     */
    public ApiCatalog scan(Path projectDir) {
        List<Path> files = ProjectFiles.javaSources(projectDir);
        ProjectSettings settings = ProjectSettings.read(projectDir);
        SourceTrees trees = SourceTrees.parse(files);
        TypeMapper mapper = new TypeMapper(trees, settings.snakeCaseJson());
        List<EntityTable> entities = new JpaEntityScanner(trees).scan();
        Context ctx = new Context(trees, mapper, entities, settings, stereotypes(trees), composedMappings(trees));
        Map<String, ApiEndpoint> endpoints = new LinkedHashMap<>();
        for (SourceTrees.TypeDecl decl : trees.types()) {
            ClassTree ct = decl.tree();
            if (isController(ct, ctx)) {
                for (ApiEndpoint e : controller(ct, ctx)) {
                    endpoints.putIfAbsent(e.routeKey(), e); // first declaration wins
                }
            }
        }
        int mvc = endpoints.size();
        for (ApiEndpoint e : new FunctionalRouteScanner(trees, settings).scan()) {
            endpoints.putIfAbsent(e.routeKey(), e);
        }
        int functional = endpoints.size() - mvc;
        int dataRest = 0;
        if (ProjectFiles.declares(projectDir, "spring-boot-starter-data-rest")
                || ProjectFiles.declares(projectDir, "spring-data-rest-webmvc")) {
            for (ApiEndpoint e : new DataRestScanner(trees, mapper, entities, settings).scan()) {
                if (endpoints.putIfAbsent(e.routeKey(), e) == null) {
                    dataRest++;
                }
            }
        }
        Path fileName = projectDir.toAbsolutePath().normalize().getFileName();
        String project = fileName == null ? "project" : fileName.toString();
        log.accept("source: " + files.size() + " Java files, " + endpoints.size() + " operations ("
                + mvc + " controller, " + functional + " functional route, " + dataRest + " Spring Data REST), "
                + entities.size() + " JPA entities");
        return new ApiCatalog(project, settings.contextPath(), new ArrayList<>(endpoints.values()),
                mapper.schemas(), entities);
    }

    /**
     * OpenAPI documents bundled with a project (API-first projects generate their controllers from them).
     *
     * @param projectDir project root
     * @return spec files
     */
    public static List<Path> bundledOpenApiSpecs(Path projectDir) {
        return ProjectFiles.openApiSpecs(projectDir);
    }

    /** Everything a scan needs, shared by the helpers. */
    private record Context(SourceTrees trees, TypeMapper mapper, List<EntityTable> entities,
                           ProjectSettings settings, Set<String> stereotypes,
                           Map<String, List<String>> composedMappings) {
    }

    private static final Set<String> MVC_MAPPINGS = Set.of("GetMapping", "PostMapping", "PutMapping",
            "PatchMapping", "DeleteMapping", "RequestMapping");
    private static final Map<String, HttpMethod> EXCHANGE_SHORTCUTS = Map.of(
            "GetExchange", HttpMethod.GET, "PostExchange", HttpMethod.POST, "PutExchange", HttpMethod.PUT,
            "PatchExchange", HttpMethod.PATCH, "DeleteExchange", HttpMethod.DELETE);

    /**
     * Controller stereotypes: {@code @RestController}, {@code @Controller} and every annotation of the project
     * that is (transitively) meta-annotated with one of them, e.g. a team's own {@code @ApiController}.
     */
    private static Set<String> stereotypes(SourceTrees trees) {
        Set<String> out = new HashSet<>(Set.of("RestController", "Controller"));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (SourceTrees.TypeDecl d : trees.types()) {
                if (d.tree().getKind() == Tree.Kind.ANNOTATION_TYPE && !out.contains(d.simpleName())
                        && d.tree().getModifiers().getAnnotations().stream()
                        .anyMatch(a -> out.contains(SourceTrees.simpleName(a)))) {
                    out.add(d.simpleName());
                    changed = true;
                }
            }
        }
        return out;
    }

    /** Base paths contributed by composed annotations that carry {@code @RequestMapping}. */
    private static Map<String, List<String>> composedMappings(SourceTrees trees) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (SourceTrees.TypeDecl d : trees.types()) {
            if (d.tree().getKind() == Tree.Kind.ANNOTATION_TYPE) {
                SourceTrees.annotation(d.tree().getModifiers(), "RequestMapping")
                        .map(a -> trees.strings(a, "value", "path")).filter(l -> !l.isEmpty())
                        .ifPresent(paths -> out.put(d.simpleName(), paths));
            }
        }
        return out;
    }

    private static boolean isController(ClassTree ct, Context ctx) {
        ModifiersTree mods = ct.getModifiers();
        if (ct.getKind() == Tree.Kind.ANNOTATION_TYPE
                || mods.getFlags().contains(javax.lang.model.element.Modifier.ABSTRACT)
                && ct.getKind() == Tree.Kind.CLASS) {
            return false; // annotations, and abstract base controllers (scanned through their subclasses)
        }
        if (mods.getAnnotations().stream().anyMatch(a -> ctx.stereotypes().contains(SourceTrees.simpleName(a)))
                || (ct.getKind() == Tree.Kind.CLASS && SourceTrees.has(mods, "RequestMapping"))) {
            return true;
        }
        // API-first interfaces (OpenAPI generator style) carry Spring MVC mappings on interface methods only.
        // (@HttpExchange interfaces are HTTP clients unless a controller implements them; see controller().)
        return ct.getKind() == Tree.Kind.INTERFACE && ct.getMembers().stream()
                .anyMatch(m -> m instanceof MethodTree mt && mvcMapping(mt.getModifiers()).isPresent());
    }

    private static Optional<AnnotationTree> mvcMapping(ModifiersTree mods) {
        for (AnnotationTree a : mods.getAnnotations()) {
            if (MVC_MAPPINGS.contains(SourceTrees.simpleName(a))) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    private static Optional<AnnotationTree> mapping(ModifiersTree mods) {
        for (AnnotationTree a : mods.getAnnotations()) {
            String n = SourceTrees.simpleName(a);
            if (MVC_MAPPINGS.contains(n) || EXCHANGE_SHORTCUTS.containsKey(n) || n.equals("HttpExchange")) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /**
     * Class-level base paths of a type: its own {@code @RequestMapping}/{@code @HttpExchange}, else the one
     * carried by a composed annotation (Spring: a directly declared mapping wins over a meta-annotation).
     * Empty when the type declares none.
     */
    private static List<String> classBases(ClassTree ct, Context ctx) {
        ModifiersTree mods = ct.getModifiers();
        Optional<AnnotationTree> own = SourceTrees.annotation(mods, "RequestMapping", "HttpExchange");
        if (own.isPresent()) {
            List<String> paths = ctx.trees().strings(own.get(), "value", "path", "url");
            return paths.isEmpty() ? List.of("") : paths;
        }
        for (AnnotationTree a : mods.getAnnotations()) {
            List<String> composed = ctx.composedMappings().get(SourceTrees.simpleName(a));
            if (composed != null) {
                return composed;
            }
        }
        return List.of();
    }

    /** A mapped handler method and the type that declares it, with the generic bindings of that type. */
    private record Declared(MethodTree method, ClassTree owner, Map<String, Tree> bindings) {
    }

    /**
     * Mapped methods of a controller: its own, then those inherited from parsed superclasses (a generic
     * {@code AbstractCrudController<T, ID>}) and interfaces, with type variables bound to the subtype's arguments.
     */
    private static List<Declared> mappedMethods(ClassTree ct, Map<String, Tree> bindings, Context ctx,
                                                Set<String> seen) {
        List<Declared> out = new ArrayList<>();
        if (!seen.add(ct.getSimpleName().toString())) {
            return out;
        }
        for (Tree member : ct.getMembers()) {
            if (member instanceof MethodTree m && mapping(m.getModifiers()).isPresent()) {
                out.add(new Declared(m, ct, bindings));
            }
        }
        List<Tree> supertypes = new ArrayList<>();
        if (ct.getExtendsClause() != null) {
            supertypes.add(ct.getExtendsClause());
        }
        supertypes.addAll(ct.getImplementsClause());
        for (Tree sup : supertypes) {
            Optional<SourceTrees.TypeDecl> decl = ctx.trees().type(TypeMapper.simpleName(sup));
            if (decl.isEmpty()) {
                continue;
            }
            ClassTree st = decl.get().tree();
            Map<String, Tree> supBindings = new LinkedHashMap<>();
            if (sup instanceof ParameterizedTypeTree pt) {
                for (int i = 0; i < st.getTypeParameters().size() && i < pt.getTypeArguments().size(); i++) {
                    Tree arg = pt.getTypeArguments().get(i);
                    Tree bound = bindings.get(TypeMapper.simpleName(arg));
                    supBindings.put(st.getTypeParameters().get(i).getName().toString(), bound != null ? bound : arg);
                }
            }
            out.addAll(mappedMethods(st, supBindings, ctx, seen));
        }
        return out;
    }

    private List<ApiEndpoint> controller(ClassTree ct, Context ctx) {
        SourceTrees trees = ctx.trees();
        String controllerName = ct.getSimpleName().toString();
        List<String> ownBases = classBases(ct, ctx);
        String resource = resourceOf(controllerName, ctx.entities());
        List<ApiEndpoint> out = new ArrayList<>();
        for (Declared d : mappedMethods(ct, Map.of(), ctx, new HashSet<>())) {
            MethodTree m = d.method();
            AnnotationTree a = mapping(m.getModifiers()).orElseThrow();
            String annotation = SourceTrees.simpleName(a);
            boolean exchange = annotation.equals("HttpExchange") || EXCHANGE_SHORTCUTS.containsKey(annotation);
            if (exchange && d.owner() == ct && ct.getKind() == Tree.Kind.INTERFACE) {
                continue; // an @HttpExchange interface on its own is an HTTP client, not an endpoint
            }
            List<HttpMethod> methods = SHORTCUTS.containsKey(annotation) ? List.of(SHORTCUTS.get(annotation))
                    : EXCHANGE_SHORTCUTS.containsKey(annotation) ? List.of(EXCHANGE_SHORTCUTS.get(annotation))
                    : trees.strings(a, "method").stream().map(HttpMethod::parse).toList();
            if (methods.isEmpty()) {
                methods = List.of(HttpMethod.GET); // @RequestMapping without method: matches all, GET is the safe pick
            }
            List<String> paths = trees.strings(a, "value", "path", "url");
            if (paths.isEmpty()) {
                paths = List.of("");
            }
            // Spring looks the class-level mapping up on the controller first, then on the declaring type.
            List<String> bases = !ownBases.isEmpty() ? ownBases
                    : d.owner() == ct ? List.of("") : classBases(d.owner(), ctx);
            if (bases.isEmpty()) {
                bases = List.of("");
            }
            Handler handler = ctx.mapper().withBindings(d.bindings(),
                    () -> handler(m, trees, ctx.mapper(), controllerName));
            if (handler == null) {
                continue;
            }
            String id = SourceTrees.annotation(m.getModifiers(), "Operation")
                    .flatMap(op -> trees.string(op, "operationId")).filter(s -> !s.isBlank())
                    .orElse(operationId(m.getName().toString(), controllerName));
            String summary = SourceTrees.annotation(m.getModifiers(), "Operation")
                    .flatMap(op -> trees.string(op, "summary")).orElse(null);
            for (String base : bases) {
                for (String path : paths) {
                    for (HttpMethod method : methods) {
                        String full = joinPath(ctx.settings().resolvePlaceholders(base),
                                ctx.settings().resolvePlaceholders(path));
                        List<ApiParam> params = withPathConstraints(full, handler.params());
                        Schema body = method.hasBody() ? wrapRoot(handler.body(), ctx) : null;
                        out.add(new ApiEndpoint(id, method, stripRegex(full), summary, List.of(controllerName),
                                params, body, resource, Set.of("source")));
                    }
                }
            }
        }
        return out;
    }

    /**
     * With {@code spring.jackson.deserialization.unwrap-root-value=true} Jackson expects every body wrapped in an
     * object named by {@code @JsonRootName} (or the class name): {@code {"user": {...}}}.
     */
    private static @Nullable Schema wrapRoot(@Nullable Schema body, Context ctx) {
        if (body == null || !ctx.settings().unwrapRootValue() || !(body instanceof RefSchema(String name))) {
            return body;
        }
        String root = ctx.trees().type(name)
                .flatMap(d -> SourceTrees.annotation(d.tree().getModifiers(), "JsonRootName"))
                .flatMap(a -> ctx.trees().string(a, "value")).orElse(name);
        return new ObjectSchema(Map.of(root, new Property(body, true, false, null)), false);
    }

    /** Generic handler names get the controller's subject: {@code get} in {@code CustomerController} → {@code getCustomer}. */
    static String operationId(String method, String controller) {
        if (!GENERIC_NAMES.contains(method)) {
            return method;
        }
        String subject = controller.replaceAll("(Rest)?(Controller|Resource|Api|Endpoint)(Impl)?$", "");
        if (subject.isEmpty()) {
            return method;
        }
        boolean many = method.equals("list") || method.equals("all") || method.startsWith("findAll")
                || method.equals("getAll") || method.equals("search") || method.equals("index");
        return method + (many ? DataRestScanner.plural(subject) : subject);
    }

    private record Handler(List<ApiParam> params, @Nullable Schema body) {
    }

    private @Nullable Handler handler(MethodTree m, SourceTrees trees, TypeMapper mapper, String controller) {
        List<ApiParam> params = new ArrayList<>();
        Schema body = null;
        for (VariableTree p : m.getParameters()) {
            ModifiersTree mods = p.getModifiers();
            List<? extends AnnotationTree> anns = mods.getAnnotations();
            String type = TypeMapper.simpleName(p.getType());
            String javaName = p.getName().toString();
            if (type.equals("MultipartFile") || type.equals("MultipartFile[]") || type.equals("Part")
                    || SourceTrees.has(mods, "RequestPart")) {
                log.accept("source: skipped multipart operation " + controller + "." + m.getName()
                        + " (k6 multipart bodies are not generated)");
                return null;
            }
            if (FRAMEWORK_TYPES.contains(type) || anns.stream()
                    .anyMatch(a -> CONTEXT_ANNOTATIONS.contains(SourceTrees.simpleName(a)))) {
                continue;
            }
            Optional<AnnotationTree> pathVar = SourceTrees.annotation(mods, "PathVariable");
            Optional<AnnotationTree> requestParam = SourceTrees.annotation(mods, "RequestParam");
            Optional<AnnotationTree> header = SourceTrees.annotation(mods, "RequestHeader");
            Optional<AnnotationTree> requestBody = SourceTrees.annotation(mods, "RequestBody");
            boolean optionalType = type.equals("Optional");
            if (pathVar.isPresent()) {
                String name = trees.string(pathVar.get(), "value", "name").orElse(javaName);
                params.add(new ApiParam(name, ParamLocation.PATH, true, mapper.map(p.getType(), anns), null));
            } else if (requestParam.isPresent() || header.isPresent()) {
                AnnotationTree a = requestParam.orElseGet(header::get);
                if (mapsTo(type)) {
                    continue; // @RequestParam Map<String,String>: open-ended, nothing to generate
                }
                String name = trees.string(a, "value", "name").orElse(javaName);
                String defaultValue = trees.string(a, "defaultValue").orElse(null);
                boolean required = !optionalType && defaultValue == null
                        && trees.string(a, "required").map(v -> !v.equals("false")).orElse(true);
                ParamLocation in = requestParam.isPresent() ? ParamLocation.QUERY : ParamLocation.HEADER;
                if (in == ParamLocation.HEADER && name.equalsIgnoreCase("Authorization")) {
                    continue; // supplied by the suite's auth configuration
                }
                params.add(new ApiParam(name, in, required, mapper.map(p.getType(), anns), defaultValue));
            } else if (requestBody.isPresent()) {
                body = mapper.map(p.getType(), anns);
            } else if (type.equals("Pageable")) {
                params.add(new ApiParam("page", ParamLocation.QUERY, false, integer(0, null), "0"));
                params.add(new ApiParam("size", ParamLocation.QUERY, false, integer(1, 100), "20"));
            } else if (type.equals("Sort")) {
                params.add(new ApiParam("sort", ParamLocation.QUERY, false,
                        ScalarSchema.of(ScalarType.STRING, null), null));
            } else if (mapper.isScalar(p.getType())) {
                // No annotation on a simple type: Spring binds it as an optional request parameter.
                params.add(new ApiParam(javaName, ParamLocation.QUERY, false, mapper.map(p.getType(), anns), null));
            } else if (!mapsTo(type)) {
                // @ModelAttribute or a plain query object: its scalar properties are query parameters.
                Schema s = mapper.map(p.getType());
                if (s instanceof RefSchema(String ref) && mapper.schemas().get(ref) != null) {
                    for (Map.Entry<String, Property> e : mapper.schemas().get(ref).properties().entrySet()) {
                        Schema ps = e.getValue().schema();
                        if (ps instanceof ScalarSchema || ps instanceof ArraySchema) {
                            params.add(new ApiParam(e.getKey(), ParamLocation.QUERY, e.getValue().required(), ps,
                                    null));
                        }
                    }
                }
            }
        }
        return new Handler(params, body);
    }

    private static boolean mapsTo(String type) {
        return type.endsWith("Map") || type.equals("Properties");
    }

    private static ScalarSchema integer(long min, @Nullable Integer max) {
        return ScalarSchema.of(ScalarType.INTEGER, "int32").withConstraints(new Constraints(null, null,
                java.math.BigDecimal.valueOf(min), max == null ? null : java.math.BigDecimal.valueOf(max), null,
                null));
    }

    /**
     * Spring path variables may carry a regex ({@code {id:\d+}}); it becomes the parameter's pattern and is
     * removed from the template. Path variables without a matching handler parameter are added as strings.
     */
    private static List<ApiParam> withPathConstraints(String path, List<ApiParam> params) {
        List<ApiParam> out = new ArrayList<>();
        Map<String, String> regexByVar = new LinkedHashMap<>();
        Matcher m = PATH_VAR.matcher(path);
        while (m.find()) {
            regexByVar.put(m.group(1), m.group(2));
        }
        for (ApiParam p : params) {
            String regex = p.in() == ParamLocation.PATH ? regexByVar.get(p.name()) : null;
            if (regex != null && p.schema() instanceof ScalarSchema s) {
                out.add(new ApiParam(p.name(), p.in(), p.required(),
                        s.withConstraints(s.constraints().overlay(new Constraints(null, null, null, null,
                                "^" + regex + "$", null))), p.defaultValue()));
            } else {
                out.add(p);
            }
        }
        for (String var : regexByVar.keySet()) {
            if (out.stream().noneMatch(p -> p.in() == ParamLocation.PATH && p.name().equals(var))) {
                out.add(new ApiParam(var, ParamLocation.PATH, true, ScalarSchema.of(ScalarType.STRING, null), null));
            }
        }
        return out;
    }

    private static String stripRegex(String path) {
        return PATH_VAR.matcher(path).replaceAll(r -> Matcher.quoteReplacement("{" + r.group(1) + "}"));
    }

    static String joinPath(String base, String path) {
        String joined = ("/" + base + "/" + path).replaceAll("/+", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined;
    }

    /** {@code CustomerController} / {@code CustomerRestController} / {@code CustomerResource} → {@code Customer}. */
    private static @Nullable String resourceOf(String controller, List<EntityTable> entities) {
        String base = controller.replaceAll("(Rest)?(Controller|Resource|Api|Endpoint)(Impl)?$", "");
        for (EntityTable e : entities) {
            if (e.entityName().toLowerCase(Locale.ROOT).equals(base.toLowerCase(Locale.ROOT))) {
                return e.entityName();
            }
        }
        return null;
    }
}
