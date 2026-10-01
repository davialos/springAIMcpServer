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
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
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
        Map<String, ApiEndpoint> endpoints = new LinkedHashMap<>();
        for (SourceTrees.TypeDecl decl : trees.types()) {
            ClassTree ct = decl.tree();
            if (isController(ct)) {
                for (ApiEndpoint e : controller(ct, trees, mapper, entities)) {
                    endpoints.putIfAbsent(e.routeKey(), e); // interface + implementation: first wins
                }
            }
        }
        Path fileName = projectDir.toAbsolutePath().normalize().getFileName();
        String project = fileName == null ? "project" : fileName.toString();
        log.accept("source: " + files.size() + " Java files, " + endpoints.size() + " operations, "
                + entities.size() + " JPA entities");
        return new ApiCatalog(project, settings.contextPath(), new ArrayList<>(endpoints.values()),
                mapper.schemas(), entities);
    }

    private static boolean isController(ClassTree ct) {
        ModifiersTree mods = ct.getModifiers();
        if (SourceTrees.has(mods, "RestController", "Controller", "RequestMapping")) {
            return true;
        }
        // API-first interfaces (OpenAPI generator style) carry the mappings on interface methods only.
        return ct.getKind() == Tree.Kind.INTERFACE && ct.getMembers().stream()
                .anyMatch(m -> m instanceof MethodTree mt && mapping(mt.getModifiers()).isPresent());
    }

    private static Optional<AnnotationTree> mapping(ModifiersTree mods) {
        return SourceTrees.annotation(mods, "GetMapping", "PostMapping", "PutMapping", "PatchMapping",
                "DeleteMapping", "RequestMapping");
    }

    private List<ApiEndpoint> controller(ClassTree ct, SourceTrees trees, TypeMapper mapper,
                                         List<EntityTable> entities) {
        String controllerName = ct.getSimpleName().toString();
        List<String> bases = SourceTrees.annotation(ct.getModifiers(), "RequestMapping")
                .map(a -> trees.strings(a, "value", "path")).filter(l -> !l.isEmpty()).orElse(List.of(""));
        String resource = resourceOf(controllerName, entities);
        List<ApiEndpoint> out = new ArrayList<>();
        for (Tree member : ct.getMembers()) {
            if (!(member instanceof MethodTree m)) {
                continue;
            }
            Optional<AnnotationTree> mapping = mapping(m.getModifiers());
            if (mapping.isEmpty()) {
                continue;
            }
            AnnotationTree a = mapping.get();
            String annotation = SourceTrees.simpleName(a);
            List<HttpMethod> methods = SHORTCUTS.containsKey(annotation)
                    ? List.of(SHORTCUTS.get(annotation))
                    : trees.strings(a, "method").stream().map(HttpMethod::parse).toList();
            if (methods.isEmpty()) {
                methods = List.of(HttpMethod.GET); // @RequestMapping without method: matches all, GET is the safe pick
            }
            List<String> paths = trees.strings(a, "value", "path");
            if (paths.isEmpty()) {
                paths = List.of("");
            }
            Handler handler = handler(m, trees, mapper, controllerName);
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
                        String full = joinPath(base, path);
                        List<ApiParam> params = withPathConstraints(full, handler.params());
                        Schema body = method.hasBody() ? handler.body() : null;
                        out.add(new ApiEndpoint(id, method, stripRegex(full), summary, List.of(controllerName),
                                params, body, resource, Set.of("source")));
                    }
                }
            }
        }
        return out;
    }

    /** Generic handler names get the controller's subject: {@code get} in {@code CustomerController} → {@code getCustomer}. */
    static String operationId(String method, String controller) {
        if (!GENERIC_NAMES.contains(method)) {
            return method;
        }
        String subject = controller.replaceAll("(Rest)?(Controller|Resource|Api|Endpoint)(Impl)?$", "");
        return subject.isEmpty() ? method : method + subject;
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
