package com.springaimcpservercommon.celfaker.contract;

import com.springaimcpservercommon.celfaker.payload.JsonValues;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The user's set of APIs with their payloads, documentation and validation endpoints.
 *
 * @param name    project name (used for the output folder and README title)
 * @param baseUrl default base URL of the system under test (overridable with {@code BASE_URL} in k6)
 * @param apis    the APIs
 */
public record ApiContract(String name, String baseUrl, List<ApiSpec> apis) {

    /** Validates ids and normalises. */
    public ApiContract {
        name = name == null || name.isBlank() ? "api-project" : name;
        baseUrl = baseUrl == null ? "http://localhost:8080" : baseUrl;
        apis = apis == null ? List.of() : List.copyOf(apis);
        Set<String> ids = new HashSet<>();
        for (ApiSpec a : apis) {
            if (!ids.add(a.id())) {
                throw new IllegalArgumentException("duplicate api id: " + a.id());
            }
        }
    }

    /**
     * Finds an API.
     *
     * @param id api id
     * @return the API, or {@code null}
     */
    public ApiSpec find(String id) {
        return apis.stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * Reads a contract file.
     *
     * @param json file content
     * @return the contract
     */
    public static ApiContract fromJson(String json) {
        return JsonValues.MAPPER.readValue(json, ApiContract.class);
    }
}
