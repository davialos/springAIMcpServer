package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.EntityTable;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.Schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Merges catalogs from several discovery sources and applies include/exclude filters.
 * <p>
 * Precedence is the argument order (first wins for schemas, summaries and parameter definitions); a later
 * source still contributes what the earlier ones lack: routes they did not see, a request body, parameters,
 * the resource entity and the JPA entities.
 */
public final class CatalogMerger {

    /** Routes never load-tested unless explicitly included: framework, docs and this library's control plane. */
    public static final List<String> DEFAULT_EXCLUDES = List.of(
            "/error", "/error/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-resources/**",
            "/dynamic-ai/admin/**", "/dynamic-ai/mcp/**");

    private CatalogMerger() {
    }

    /**
     * Merges catalogs.
     *
     * @param catalogs catalogs, highest precedence first
     * @return the merged catalog with unique, JS-safe endpoint ids
     */
    public static ApiCatalog merge(List<ApiCatalog> catalogs) {
        Map<String, ApiEndpoint> byRoute = new LinkedHashMap<>();
        Map<String, ObjectSchema> schemas = new LinkedHashMap<>();
        Map<String, EntityTable> entities = new LinkedHashMap<>();
        String project = null;
        String basePath = null;
        for (ApiCatalog c : catalogs) {
            if (project == null || project.equals("project")) {
                project = c.project();
            }
            if (basePath == null) {
                basePath = c.basePath();
            }
            c.schemas().forEach(schemas::putIfAbsent);
            c.entities().forEach(e -> entities.putIfAbsent(e.entityName(), e));
            for (ApiEndpoint e : c.endpoints()) {
                byRoute.merge(e.routeKey(), e, CatalogMerger::combine);
            }
        }
        return new ApiCatalog(project == null ? "project" : project, basePath, uniqueIds(byRoute.values()),
                schemas, new ArrayList<>(entities.values()));
    }

    private static ApiEndpoint combine(ApiEndpoint first, ApiEndpoint later) {
        Map<String, ApiParam> params = new LinkedHashMap<>();
        for (ApiParam p : first.params()) {
            params.put(p.in() + ":" + p.name(), p);
        }
        for (ApiParam p : later.params()) {
            // Path variables may be named differently ({id} vs {userId}); the first source's template wins.
            boolean pathAlreadyCovered = p.in() == com.springaimcpservercommon.loadtest.model.ParamLocation.PATH
                    && first.params(p.in()).size() >= countVars(first.path());
            if (!pathAlreadyCovered) {
                params.putIfAbsent(p.in() + ":" + p.name(), p);
            }
        }
        Schema body = first.body();
        if (isUnknown(body) && !isUnknown(later.body())) {
            body = later.body();
        }
        Set<String> sources = new LinkedHashSet<>(first.sources());
        sources.addAll(later.sources());
        List<String> tags = first.tags().isEmpty() ? later.tags() : first.tags();
        return new ApiEndpoint(first.id(), first.method(), first.path(),
                first.summary() != null ? first.summary() : later.summary(), tags, new ArrayList<>(params.values()),
                body, first.resource() != null ? first.resource() : later.resource(), sources,
                first.responseSchema() != null ? first.responseSchema() : later.responseSchema(),
                first.access() != null ? first.access() : later.access(),
                first.bodyType() != null ? first.bodyType() : later.bodyType());
    }

    private static boolean isUnknown(Schema s) {
        return s == null || (s instanceof ObjectSchema o && o.freeForm());
    }

    private static int countVars(String path) {
        return (int) path.chars().filter(ch -> ch == '{').count();
    }

    private static List<ApiEndpoint> uniqueIds(Iterable<ApiEndpoint> endpoints) {
        List<ApiEndpoint> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (ApiEndpoint e : endpoints) {
            String base = Names.jsIdentifier(e.id());
            String id = base;
            if (used.contains(id) && !e.tags().isEmpty()) {
                String tag = e.tags().getFirst().replaceAll("(Rest)?(Controller|Resource|Api)$", "");
                id = Names.jsIdentifier(tag.substring(0, 1).toLowerCase(Locale.ROOT) + tag.substring(1) + "_" + base);
            }
            for (int n = 2; used.contains(id); n++) {
                id = base + "_" + n;
            }
            used.add(id);
            out.add(e.withId(id));
        }
        return out;
    }

    /**
     * Moves a catalog onto another base path: an OpenAPI document whose server URL is
     * {@code /petclinic/api} describes paths relative to it, while the project's controllers are relative to the
     * servlet context path {@code /petclinic}; rebasing prefixes the document's paths with {@code /api} so both
     * agree and the base URL is the context path.
     *
     * @param catalog  catalog
     * @param prefix   prefix to put in front of every path (e.g. {@code /api})
     * @param basePath the new base path (the servlet context path), or {@code null}
     * @return the rebased catalog
     */
    public static ApiCatalog rebase(ApiCatalog catalog, String prefix, @org.jspecify.annotations.Nullable String basePath) {
        List<ApiEndpoint> moved = new ArrayList<>();
        for (ApiEndpoint e : catalog.endpoints()) {
            String path = (prefix + "/" + e.path()).replaceAll("/+", "/");
            path = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            moved.add(new ApiEndpoint(e.id(), e.method(), path, e.summary(), e.tags(), e.params(), e.body(),
                    e.resource(), e.sources(), e.responseSchema(), e.access(), e.bodyType()));
        }
        return new ApiCatalog(catalog.project(), basePath, moved, catalog.schemas(), catalog.entities());
    }

    /**
     * Keeps the endpoints that match an include pattern (all when none is given) and no exclude pattern.
     * A pattern is an Ant-style path ({@code /api/**}, {@code /users/*}) optionally prefixed by a method
     * ({@code DELETE /api/**}).
     *
     * @param catalog  catalog
     * @param includes include patterns
     * @param excludes exclude patterns
     * @return the filtered catalog
     */
    public static ApiCatalog filter(ApiCatalog catalog, List<String> includes, List<String> excludes) {
        List<ApiEndpoint> kept = new ArrayList<>();
        for (ApiEndpoint e : catalog.endpoints()) {
            boolean included = includes.isEmpty() || includes.stream().anyMatch(p -> matches(p, e));
            boolean excluded = excludes.stream().anyMatch(p -> matches(p, e));
            if (included && !excluded) {
                kept.add(e);
            }
        }
        return new ApiCatalog(catalog.project(), catalog.basePath(), kept, catalog.schemas(), catalog.entities());
    }

    static boolean matches(String pattern, ApiEndpoint e) {
        String p = pattern.trim();
        int space = p.indexOf(' ');
        if (space > 0) {
            String method = p.substring(0, space);
            if (!method.equals("*") && HttpMethod.parse(method) != e.method()) {
                return false;
            }
            p = p.substring(space + 1).trim();
        }
        if (p.equals(e.id())) {
            return true;
        }
        return antToRegex(p).matcher(e.path()).matches();
    }

    private static Pattern antToRegex(String ant) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < ant.length(); i++) {
            char c = ant.charAt(i);
            if (c == '*' && i + 1 < ant.length() && ant.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        // "/x/**" also matches "/x"
        String s = re.toString().replace(Pattern.quote("/") + ".*", "(/.*)?");
        return Pattern.compile(s);
    }
}
