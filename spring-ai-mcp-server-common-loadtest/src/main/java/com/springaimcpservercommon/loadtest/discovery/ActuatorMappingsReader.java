package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.ApiEndpoint;
import com.springaimcpservercommon.loadtest.model.ApiParam;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a running application's {@code /actuator/mappings} document. It lists every route actually registered,
 * including routes added at runtime (this library's dynamic endpoints, LLD-04), but carries no request schemas:
 * merge it with a source scan or an OpenAPI document for payloads.
 */
public final class ActuatorMappingsReader {

    private static final Pattern PATH_VAR = Pattern.compile("\\{\\*?([^}:]+)(?::[^}]*)?}");

    private final Consumer<String> log;

    /**
     * Creates a reader.
     *
     * @param log receives a summary line
     */
    public ActuatorMappingsReader(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Parses a mappings document.
     *
     * @param document JSON text of {@code /actuator/mappings}
     * @return the catalog (routes only)
     */
    public ApiCatalog read(String document) {
        JsonNode root = Documents.parse(document);
        List<ApiEndpoint> out = new ArrayList<>();
        for (JsonNode context : root.path("contexts")) {
            for (JsonNode servlet : context.path("mappings").path("dispatcherServlets")) {
                for (JsonNode mapping : servlet) {
                    JsonNode details = mapping.path("details");
                    JsonNode conditions = details.path("requestMappingConditions");
                    if (conditions.isMissingNode()) {
                        continue; // resource handlers, welcome page
                    }
                    String className = details.path("handlerMethod").path("className").asString("");
                    String tag = className.substring(className.lastIndexOf('.') + 1);
                    String name = details.path("handlerMethod").path("name").asString("op");
                    List<HttpMethod> methods = new ArrayList<>();
                    conditions.path("methods").forEach(m -> methods.add(HttpMethod.parse(m.asString())));
                    if (methods.isEmpty()) {
                        methods.add(HttpMethod.GET);
                    }
                    for (JsonNode pattern : conditions.path("patterns")) {
                        String path = PATH_VAR.matcher(pattern.asString())
                                .replaceAll(r -> Matcher.quoteReplacement("{" + r.group(1) + "}"));
                        List<ApiParam> params = new ArrayList<>();
                        Matcher m = PATH_VAR.matcher(path);
                        while (m.find()) {
                            params.add(new ApiParam(m.group(1), ParamLocation.PATH, true,
                                    ScalarSchema.of(ScalarType.STRING, null), null));
                        }
                        for (HttpMethod method : methods) {
                            out.add(new ApiEndpoint(name, method, path, null, tag.isEmpty() ? List.of() : List.of(tag),
                                    params, method.hasBody() ? ObjectSchema.freeFormObject() : null, null,
                                    Set.of("actuator")));
                        }
                    }
                }
            }
        }
        log.accept("actuator: " + out.size() + " routes");
        return new ApiCatalog("project", null, out, java.util.Map.of(), List.of());
    }
}
