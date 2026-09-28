/**
 * Spring AI 2.x agent runtime (LLD-06/07, ADR-0008, ADR-0014): ChatClient assembly, tool bridge,
 * principal-scoped execution, advisors, AI write guard, and model routing.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code agent} — AgentDefinition and related value types</li>
 *   <li>{@code tool} — ToolBinding, ToolSource, SecuredToolCallback, ToolBridge, ToolResultEnvelope</li>
 *   <li>{@code guard} — Hibernate AI write guard (ADR-0014)</li>
 *   <li>{@code advisor} — Spring AI advisors (InvocationGuard, UsageMetering)</li>
 *   <li>{@code model} — ModelRouter port</li>
 * </ul>
 */
@NullMarked
package com.springaimcpservercommon.ai;

import org.jspecify.annotations.NullMarked;
