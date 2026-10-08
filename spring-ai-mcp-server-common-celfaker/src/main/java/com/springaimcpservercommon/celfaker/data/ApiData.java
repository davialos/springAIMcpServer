package com.springaimcpservercommon.celfaker.data;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Generated request data of one API.
 *
 * @param apiId    the API
 * @param valid    request bodies that satisfy every rule
 * @param invalid  request bodies that must be rejected
 * @param warnings things the user should look at (rule without satisfying input, unknown parameter, ...)
 */
public record ApiData(String apiId, List<JsonNode> valid, List<InvalidCase> invalid, List<String> warnings) {
}
