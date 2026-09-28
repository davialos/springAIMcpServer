package com.springaimcpservercommon.webmvc.endpoint;

/**
 * HTTP methods supported by dynamic endpoints in v1 (LLD-04 §2).
 *
 * <p>GET and POST are supported in v1. PUT/PATCH/DELETE are reserved for v2 write endpoints.
 */
public enum DaiHttpMethod {
    GET,
    POST
}
