/**
 * Dynamic endpoint engine and admin REST API for servlet hosts (LLD-04, LLD-08).
 *
 * <p>Published {@link com.springaimcpservercommon.webmvc.endpoint.EndpointDefinition} revisions
 * are turned into live Spring MVC routes under {@code /dynamic-ai/api/**} via
 * {@link com.springaimcpservercommon.webmvc.endpoint.DynamicEndpointRegistrar}.
 * Each request is handled by
 * {@link com.springaimcpservercommon.webmvc.endpoint.GenericDynamicHandler}, which enforces the
 * full pipeline: kill-switch → authz → validation → rate-limit → execute → shape → audit.
 *
 * <p>Errors use RFC 9457 {@code application/problem+json} (never stack traces, SQL, or prompt text).
 */
@NullMarked
package com.springaimcpservercommon.webmvc;

import org.jspecify.annotations.NullMarked;
