/**
 * Hibernate write-guard for AI read scope (ADR-0014, LLD-06 §6).
 *
 * <p>{@link com.springaimcpservercommon.ai.guard.AiReadScope} flags the current virtual thread;
 * {@link com.springaimcpservercommon.ai.guard.AiWriteGuardIntegrator} installs Hibernate event
 * listeners that throw when a DML is attempted inside that scope.
 */
@NullMarked
package com.springaimcpservercommon.ai.guard;

import org.jspecify.annotations.NullMarked;
