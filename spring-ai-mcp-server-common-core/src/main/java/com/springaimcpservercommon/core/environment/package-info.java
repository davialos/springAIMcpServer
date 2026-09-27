/**
 * Spring-free environment containment (LLD-12 §2, ADR-0011): tier resolution (explicit tier + production profile
 * heuristic, strictest wins, UNKNOWN treated as PROD), the capability matrix and the time-boxed production override.
 * The autoconfigure module adapts Spring's {@code Environment} to {@link
 * com.springaimcpservercommon.core.environment.EnvironmentSignals}.
 */
@NullMarked
package com.springaimcpservercommon.core.environment;

import org.jspecify.annotations.NullMarked;
