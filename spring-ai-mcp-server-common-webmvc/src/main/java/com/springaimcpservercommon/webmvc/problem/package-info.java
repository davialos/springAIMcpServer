/**
 * RFC 9457 {@code application/problem+json} support for the dynamic endpoint engine (LLD-04 §4).
 *
 * <p>All error responses use {@link com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory}
 * to build safe, structured problem details. Stack traces, SQL text, prompt content and internal
 * identifiers are never included.
 */
@NullMarked
package com.springaimcpservercommon.webmvc.problem;

import org.jspecify.annotations.NullMarked;
