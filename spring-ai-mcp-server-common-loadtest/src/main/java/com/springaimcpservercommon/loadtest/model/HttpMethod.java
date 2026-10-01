package com.springaimcpservercommon.loadtest.model;

import java.util.Locale;

/**
 * HTTP methods a discovered endpoint can answer.
 */
public enum HttpMethod {
    GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS;

    /**
     * Whether a request with this method changes server state.
     *
     * @return {@code true} for POST, PUT, PATCH and DELETE
     */
    public boolean isWrite() {
        return this == POST || this == PUT || this == PATCH || this == DELETE;
    }

    /**
     * Whether a request with this method may carry a JSON body.
     *
     * @return {@code true} for POST, PUT and PATCH
     */
    public boolean hasBody() {
        return this == POST || this == PUT || this == PATCH;
    }

    /**
     * Parses a method name, case-insensitively; accepts Spring's {@code RequestMethod.GET} form.
     *
     * @param value method name
     * @return the method
     * @throws IllegalArgumentException for an unknown method
     */
    public static HttpMethod parse(String value) {
        String name = value.substring(value.lastIndexOf('.') + 1).trim().toUpperCase(Locale.ROOT);
        return valueOf(name);
    }
}
