/**
 * Runtime discovery for the load-test generator: {@code GET /actuator/loadtest} answers with an OpenAPI 3 document of
 * the routes this application really serves, with request/response shapes, Bean Validation constraints and
 * method-security access. Read by {@code loadtest generate --runtime <url>}.
 */
@NullMarked
package com.springaimcpservercommon.loadtest.runtime;

import org.jspecify.annotations.NullMarked;
