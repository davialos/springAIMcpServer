/**
 * MCP authorization glue (LLD-07 §5.3–5.5, ADR-0016): per-call scope ∩ grant evaluation with step-up challenges and
 * the approved-client gate. Transport concerns (Origin validation, session binding) live in the mcp module.
 */
@NullMarked
package com.springaimcpservercommon.security.mcp;

import org.jspecify.annotations.NullMarked;
