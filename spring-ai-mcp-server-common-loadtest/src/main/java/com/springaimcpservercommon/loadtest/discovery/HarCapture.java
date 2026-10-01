package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import com.springaimcpservercommon.loadtest.model.HttpMethod;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a browser recording (HAR) yields: the API operations seen, and every successful call in recorded order
 * with the values that were actually sent, for recorded data and journey replay.
 *
 * @param catalog      operations inferred from the recording (path templates, parameters, body schemas)
 * @param observations successful API calls in the order they happened
 * @param origin       scheme, host and port of the recorded API (e.g. {@code https://staging.example.com})
 */
public record HarCapture(ApiCatalog catalog, List<Observation> observations, @Nullable String origin) {

    /** Compact constructor: defensive copy. */
    public HarCapture {
        observations = List.copyOf(observations);
    }

    /**
     * One recorded call.
     *
     * @param method      HTTP method
     * @param template    path template it was matched to (relative to the base path)
     * @param pathValues  concrete values of the template's variables, in template order
     * @param query       query parameters as sent (decoded)
     * @param headers     custom, non-sensitive request headers as sent
     * @param body        JSON request body, if any
     * @param status      response status
     * @param startedAtMs start time, epoch milliseconds
     * @param response    JSON response body, if recorded
     */
    public record Observation(HttpMethod method, String template, List<String> pathValues,
                              Map<String, List<String>> query, Map<String, String> headers, @Nullable JsonNode body,
                              int status, long startedAtMs, @Nullable JsonNode response) {

        /** Compact constructor: ordered, unmodifiable copies. */
        public Observation {
            pathValues = List.copyOf(pathValues);
            query = Collections.unmodifiableMap(new LinkedHashMap<>(query));
            headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }

        /**
         * Merge key of the operation this call belongs to.
         *
         * @return route key, as {@link com.springaimcpservercommon.loadtest.model.ApiEndpoint#routeKey()}
         */
        public String routeKey() {
            return com.springaimcpservercommon.loadtest.model.ApiEndpoint.routeKey(method, template);
        }
    }
}
