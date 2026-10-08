package com.springaimcpservercommon.celfaker.contract;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

import java.util.List;
import java.util.Map;

/**
 * One API as the user documents it: where it is, what it takes, what it returns, how it is validated.
 *
 * @param id              unique id used by workflows and file names (letters, digits, {@code _-})
 * @param name            display name
 * @param method          HTTP method
 * @param path            path with {@code {placeholders}}
 * @param description     what the API does (documentation shown in the dashboard and the generated README)
 * @param role            ACTION or VALIDATION
 * @param sysObject       sys object code for top-level body fields (defaults to the id)
 * @param headers         constant request headers
 * @param requestExample  example request body; its fields become parameters
 * @param responseExample example response body (used to propose variable extraction)
 * @param selectedPaths   body paths to use as parameters (empty = all)
 * @param mapPaths        body paths to treat as one MAP parameter
 * @param rules           CEL rules the request must satisfy (bool); drive valid and negative data
 * @param expectedStatus  accepted statuses for valid requests (default 200, 201, 202, 204)
 * @param invalidStatus   statuses expected for invalid requests (default 400, 422)
 * @param validates       id of the ACTION this API validates (VALIDATION only)
 * @param expectBody      subset the response body must contain (VALIDATION only)
 */
public record ApiSpec(String id, String name, String method, String path, String description, ApiRole role,
                      String sysObject, Map<String, String> headers, JsonNode requestExample,
                      JsonNode responseExample, List<String> selectedPaths, List<String> mapPaths,
                      List<String> rules, List<Integer> expectedStatus, List<Integer> invalidStatus,
                      String validates, JsonNode expectBody) {

    /** Normalises omitted fields. */
    public ApiSpec {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("api id must match [A-Za-z0-9_-]+: " + id);
        }
        name = name == null ? id : name;
        method = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        path = path == null ? "/" : path;
        description = description == null ? "" : description;
        role = role == null ? ApiRole.ACTION : role;
        sysObject = sysObject == null || sysObject.isBlank() ? id.replaceAll("[^A-Za-z0-9_]", "_") : sysObject;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        requestExample = requestExample == null ? MissingNode.getInstance() : requestExample;
        responseExample = responseExample == null ? MissingNode.getInstance() : responseExample;
        selectedPaths = selectedPaths == null ? List.of() : List.copyOf(selectedPaths);
        mapPaths = mapPaths == null ? List.of() : List.copyOf(mapPaths);
        rules = rules == null ? List.of() : List.copyOf(rules);
        expectedStatus = expectedStatus == null || expectedStatus.isEmpty() ? List.of(200, 201, 202, 204) : List.copyOf(expectedStatus);
        invalidStatus = invalidStatus == null || invalidStatus.isEmpty() ? List.of(400, 422) : List.copyOf(invalidStatus);
        expectBody = expectBody == null ? MissingNode.getInstance() : expectBody;
    }

    /**
     * Whether the API has a JSON body worth generating data for.
     *
     * @return true for an ACTION with an object example
     */
    public boolean hasBody() {
        return role == ApiRole.ACTION && requestExample.isObject();
    }
}
