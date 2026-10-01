/**
 * Spring-free guardrails for agent turns (LLD-06 §8, F-76): validation of the user's prompt before it reaches a
 * model (malicious-content detection and business-scope relevance against the effective catalog) and detection and
 * redaction of personal data before an answer reaches the user.
 *
 * <p>Extension points are SPIs a host implements as ordinary beans: {@link
 * com.springaimcpservercommon.core.guard.PromptValidator} (extra prompt checks) and {@link
 * com.springaimcpservercommon.core.guard.PiiDetector} (extra personal-data formats). Nothing here logs prompt or
 * answer content.
 */
@NullMarked
package com.springaimcpservercommon.core.guard;

import org.jspecify.annotations.NullMarked;
