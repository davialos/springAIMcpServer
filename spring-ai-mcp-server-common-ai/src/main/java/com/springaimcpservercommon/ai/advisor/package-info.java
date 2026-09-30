/**
 * Spring AI {@code CallAdvisor} implementations for agent invocation governance (LLD-06 §4).
 *
 * <ul>
 *   <li>{@link com.springaimcpservercommon.ai.advisor.InvocationGuardAdvisor} — kill switch, input
 *       guardrails, budget pre-check. Runs at {@code HIGHEST_PRECEDENCE + 200}.</li>
 *   <li>{@link com.springaimcpservercommon.ai.advisor.UsageMeteringAdvisor} — post-turn token
 *       metering to Micrometer and the budget sink. Runs at {@code LOWEST_PRECEDENCE}.</li>
 * </ul>
 */
@NullMarked
package com.springaimcpservercommon.ai.advisor;

import org.jspecify.annotations.NullMarked;
