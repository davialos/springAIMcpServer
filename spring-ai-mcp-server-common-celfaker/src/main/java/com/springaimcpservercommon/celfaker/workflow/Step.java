package com.springaimcpservercommon.celfaker.workflow;

import java.util.List;

/**
 * One API call in a workflow (a node of the dashboard canvas).
 *
 * @param id          unique step id
 * @param api         id of the {@link com.springaimcpservercommon.celfaker.contract.ApiSpec} it calls
 * @param dependsOn   steps that must finish first (the edges of the canvas)
 * @param extract     variables to take from the response
 * @param inject      values to put into the request (typically {@code {{previousStep.variable}}})
 * @param expectStatus statuses that count as success; empty = the API's expected statuses
 * @param thinkTime   pause after the step, in seconds
 * @param x           canvas position
 * @param y           canvas position
 */
public record Step(String id, String api, List<String> dependsOn, List<Extract> extract, List<Inject> inject,
                   List<Integer> expectStatus, double thinkTime, double x, double y) {

    /** Normalises omitted fields. */
    public Step {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("step id must match [A-Za-z][A-Za-z0-9_]*: " + id);
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        extract = extract == null ? List.of() : List.copyOf(extract);
        inject = inject == null ? List.of() : List.copyOf(inject);
        expectStatus = expectStatus == null ? List.of() : List.copyOf(expectStatus);
    }
}
