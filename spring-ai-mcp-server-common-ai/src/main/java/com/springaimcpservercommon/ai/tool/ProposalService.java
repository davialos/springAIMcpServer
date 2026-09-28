package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;

import java.util.UUID;

/**
 * Port: creates a {@code ChangeProposal} record when a mutating tool is invoked in
 * {@link WriteMode#PROPOSE} mode (LLD-11 §5, ADR-0009).
 *
 * <p>The proposal captures the tool name, tool-input JSON, the governing binding id and
 * the calling principal, and is persisted to the {@code dai_proposals} table (LLD-15).
 * It remains in {@code PENDING} state until the principal reviews, edits, confirms or
 * rejects it through the Review API.
 *
 * <p>The default no-op implementation in {@code autoconfigure} returns a synthetic UUID and
 * does not persist — it is replaced by the persistence module's real implementation when
 * the database module is on the classpath.
 *
 * <p>Not a Spring {@code @Component} — registered as a {@code @Bean} by {@code DaiAiAutoConfiguration}
 * with {@code @ConditionalOnMissingBean(ProposalService.class)}.
 */
@NullMarked
@FunctionalInterface
public interface ProposalService {

    /**
     * Creates a change proposal for a mutating tool invocation.
     *
     * <p>This method must be idempotent with respect to the same
     * {@code (bindingId, toolInput)} within a single agent turn; callers guarantee
     * uniqueness by including the turn ID in the request context.
     *
     * @param toolName   stable tool name (from {@link ToolBinding#toolName()})
     * @param toolInput  raw JSON input string from the model (may contain PII — never log)
     * @param bindingId  the governing tool binding UUID
     * @param principal  the calling principal whose identity is recorded on the proposal
     * @return the unique proposal UUID, persisted and ready for the Review API
     */
    UUID createProposal(String toolName, String toolInput, UUID bindingId, DaiPrincipal principal);
}
