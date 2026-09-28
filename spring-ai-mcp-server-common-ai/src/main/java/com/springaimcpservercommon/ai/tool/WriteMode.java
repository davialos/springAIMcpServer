package com.springaimcpservercommon.ai.tool;

/**
 * Whether a tool binding executes a host action directly or creates a reviewed change proposal
 * (LLD-07 §2, ADR-0009).
 *
 * <p>Mutating bindings are always {@link #PROPOSE} — the model never triggers direct writes.
 * {@link #EXECUTE} is valid only for read-only operations ({@code @AiExposedAction(readOnly=true)}).
 */
public enum WriteMode {
    /**
     * The delegate is invoked immediately as the caller.
     * Only valid for {@code readOnly=true} operations.
     */
    EXECUTE,
    /**
     * The delegate is NOT called by the model. Instead, a {@code ChangeProposal} is created
     * (LLD-11) and returned to the model as {@code {status: PROPOSED, proposalId}}.
     * The proposal is applied later by the confirming user through the review API.
     */
    PROPOSE
}
