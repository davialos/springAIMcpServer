/**
 * Per-turn guardrails of the agent runtime (LLD-06 §8, F-76): prompt validation before the model is called, PII
 * redaction of the prompt, and redaction, length limiting and structured display of the answer before the user sees
 * it. Built on the Spring-free {@code core.guard} and {@code core.display} packages.
 */
@NullMarked
package com.springaimcpservercommon.ai.safety;

import org.jspecify.annotations.NullMarked;
