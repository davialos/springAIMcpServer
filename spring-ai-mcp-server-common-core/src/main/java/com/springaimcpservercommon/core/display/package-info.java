/**
 * Structured, backend-controlled presentation of agent answers (LLD-06 §8.3, LLD-13 §3): a JSON display template
 * authored with the agent decides which values of an answer are shown, with which labels, formats and masks, and
 * {@link com.springaimcpservercommon.core.display.StructuredResponseRenderer} turns an answer into a display tree of
 * text, field and table blocks from which personal data has been removed. Spring-free; rendered through the single
 * canonical JSON writer (ADR-0020).
 */
@NullMarked
package com.springaimcpservercommon.core.display;

import org.jspecify.annotations.NullMarked;
