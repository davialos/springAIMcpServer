package com.springaimcpservercommon.celfaker.workflow;

/**
 * Puts a value into a step's request.
 *
 * @param target {@code path.<name>} (fills {@code {name}} in the path), {@code query.<name>}, {@code header.<Name>} or
 *               {@code body.<path>}
 * @param value  text with {@code {{stepId.variable}}}, {@code {{env.NAME}}}, {@code {{iter}}}, {@code {{vu}}} or
 *               {@code {{uuid}}} placeholders
 */
public record Inject(String target, String value) {
}
