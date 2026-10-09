package com.springaimcpservercommon.celfaker.workflow;

import tools.jackson.databind.JsonNode;

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
 * @param assertions  checks on the response (status, header or body field against a value)
 * @param body        custom request body (replaces the generated one); {@code null} = generated
 * @param invalidCase reason of a generated invalid case to send instead (e.g. {@code customer.age:missing}); empty = none.
 *                    Combine with {@code expectStatus} (e.g. 422) to test that the API rejects it
 * @param thinkTime   pause after the step, in seconds
 * @param x           canvas position
 * @param y           canvas position
 */
public record Step(String id, String api, List<String> dependsOn, List<Extract> extract, List<Inject> inject,
                   List<Integer> expectStatus, List<Assertion> assertions, JsonNode body, String invalidCase,
                   double thinkTime, double x, double y) {

    /** Normalises omitted fields. */
    public Step {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("step id must match [A-Za-z][A-Za-z0-9_]*: " + id);
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        extract = extract == null ? List.of() : List.copyOf(extract);
        inject = inject == null ? List.of() : List.copyOf(inject);
        expectStatus = expectStatus == null ? List.of() : List.copyOf(expectStatus);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        body = body == null || body.isNull() || body.isMissingNode() ? null : body;
        invalidCase = invalidCase == null ? "" : invalidCase;
    }
}
