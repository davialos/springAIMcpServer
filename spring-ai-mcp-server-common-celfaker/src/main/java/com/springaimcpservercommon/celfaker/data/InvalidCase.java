package com.springaimcpservercommon.celfaker.data;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * A request body that must be rejected.
 *
 * @param reason         what is wrong ({@code customer.age:wrong_type}, {@code rule:<cel>})
 * @param field          the parameter(s) at fault
 * @param body           the full request body
 * @param expectedStatus statuses the API may answer with
 */
public record InvalidCase(String reason, String field, JsonNode body, List<Integer> expectedStatus) {
}
