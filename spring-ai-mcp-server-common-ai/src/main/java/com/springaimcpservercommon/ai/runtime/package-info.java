/**
 * Agent runtime port interfaces and streaming event types (LLD-06, LLD-13).
 *
 * <p>{@link com.springaimcpservercommon.ai.runtime.AgentInvoker} is the primary port for
 * executing agent turns — both synchronous JSON responses and SSE streams.
 * {@link com.springaimcpservercommon.ai.runtime.StreamEvent} is the sealed event hierarchy
 * sent over the SSE channel.
 *
 * <p>Implementations live in the {@code autoconfigure} module (wired as beans) so the core
 * library has no Spring component-scan side effects.
 */
@NullMarked
package com.springaimcpservercommon.ai.runtime;

import org.jspecify.annotations.NullMarked;
