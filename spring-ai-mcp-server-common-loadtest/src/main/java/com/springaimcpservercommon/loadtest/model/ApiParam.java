package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;

/**
 * A path, query or header parameter of an endpoint.
 *
 * @param name         parameter name on the wire
 * @param in           location
 * @param required     whether the parameter must be sent
 * @param schema       value schema
 * @param defaultValue declared default, if any
 */
public record ApiParam(String name, ParamLocation in, boolean required, Schema schema,
                       @Nullable String defaultValue) {
}
