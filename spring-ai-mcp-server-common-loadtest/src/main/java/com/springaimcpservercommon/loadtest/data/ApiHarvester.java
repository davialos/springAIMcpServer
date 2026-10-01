package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.discovery.Documents;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Fills real-data pools from the running backend itself: for a pool like {@code orders.id} it calls a
 * parameterless collection endpoint of the same resource ({@code GET /api/orders}) and collects the matching
 * property from the returned records (a JSON array, or a page wrapper such as {@code content}, {@code items},
 * {@code data}, {@code results} or HAL {@code _embedded}). Values the API returns exist by construction; when
 * a database is configured they are checked there as well.
 */
public final class ApiHarvester {

    private static final List<String> WRAPPERS = List.of("content", "items", "data", "results", "records", "rows",
            "list", "elements", "values");

    private final String baseUrl;
    private final Map<String, String> headers;
    private final Consumer<String> log;

    /**
     * Creates a harvester.
     *
     * @param baseUrl base URL of the running application (with context path)
     * @param headers headers for every call (e.g. {@code Authorization})
     * @param log     receives one line per harvested pool or failure
     */
    public ApiHarvester(String baseUrl, Map<String, String> headers, Consumer<String> log) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.headers = Map.copyOf(headers);
        this.log = log;
    }

    /**
     * Harvests values for the given pools.
     *
     * @param catalog discovered APIs
     * @param index   table index (maps collection paths to tables)
     * @param pools   pools to fill
     * @param limit   maximum values per pool
     * @return pool key → values (only pools that yielded values)
     */
    public Map<String, List<Object>> harvest(ApiCatalog catalog, TableIndex index, Iterable<PoolRef> pools,
                                             int limit) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        Map<String, JsonNode> responses = new LinkedHashMap<>();
        for (PoolRef pool : pools) {
            for (ApiEndpoint e : catalog.endpoints()) {
                if (!isCollection(e)) {
                    continue;
                }
                Optional<TableIndex.TableRef> t = index.resolve(lastSegment(e.path()));
                if (t.isEmpty() || !t.get().table().equalsIgnoreCase(pool.table())) {
                    continue;
                }
                JsonNode body = responses.computeIfAbsent(e.path(), p -> fetch(p, e));
                List<Object> values = extract(body, pool.column(), t.get(), limit);
                if (!values.isEmpty()) {
                    out.put(pool.key(), values);
                    log.accept("harvest: " + pool.key() + " <- GET " + e.path() + " (" + values.size() + " values)");
                    break;
                }
            }
        }
        return out;
    }

    private static boolean isCollection(ApiEndpoint e) {
        return e.method() == HttpMethod.GET && e.params(ParamLocation.PATH).isEmpty()
                && e.params().stream().noneMatch(p -> p.required());
    }

    static @Nullable String lastSegment(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String s = parts[i];
            if (!s.isBlank() && !s.startsWith("{") && !s.matches("v\\d+") && !s.equals("api")) {
                return s;
            }
        }
        return null;
    }

    private JsonNode fetch(String path, ApiEndpoint e) {
        String url = baseUrl + path;
        try {
            return Documents.parse(Documents.text(url, headers));
        } catch (RuntimeException ex) {
            log.accept("harvest: GET " + e.path() + " failed: " + ex.getMessage());
            return Documents.parse("{}");
        }
    }

    private static List<Object> extract(JsonNode body, String column, TableIndex.TableRef t, int limit) {
        JsonNode records = records(body);
        Set<String> names = new LinkedHashSet<>();
        if (t.entity() != null) {
            t.entity().fieldColumns().forEach((field, col) -> {
                if (col.equalsIgnoreCase(column)) {
                    names.add(field);
                }
            });
        }
        names.add(column);
        Set<Object> values = new LinkedHashSet<>();
        for (JsonNode r : records) {
            for (var p : r.properties()) {
                boolean match = names.stream().anyMatch(n -> Names.normalize(n).equals(Names.normalize(p.getKey())));
                JsonNode v = p.getValue();
                if (match && v.isValueNode() && !v.isNull()) {
                    values.add(v.isIntegralNumber() ? (Object) v.asLong() : v.isNumber() ? v.decimalValue()
                            : v.isBoolean() ? (Object) v.asBoolean() : v.asString());
                }
            }
            if (values.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(values);
    }

    private static JsonNode records(JsonNode body) {
        if (body.isArray()) {
            return body;
        }
        for (String w : WRAPPERS) {
            if (body.path(w).isArray()) {
                return body.path(w);
            }
        }
        JsonNode embedded = body.path("_embedded");
        for (JsonNode v : embedded) {
            if (v.isArray()) {
                return v;
            }
        }
        return body.isObject() && !body.isEmpty() ? Documents.json().createArrayNode().add(body)
                : Documents.json().createArrayNode();
    }
}
