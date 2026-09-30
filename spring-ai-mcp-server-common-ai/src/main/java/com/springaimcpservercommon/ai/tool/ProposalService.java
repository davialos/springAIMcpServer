package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.UUID;

/**
 * Port: creates a {@code ChangeProposal} when a mutating tool is invoked in {@link WriteMode#PROPOSE} mode
 * (LLD-11 §2, ADR-0009). Nothing is written to the host: the proposal only records what the tool asked for, owned by
 * the caller, and waits for the caller (or an approver) to review, confirm or reject it through the review API.
 *
 * <p>The store-backed implementation lives in {@code autoconfigure}. Without it the default refuses, so a proposal
 * that was never stored is never reported as created.
 *
 * <p>Not a Spring {@code @Component}; registered by {@code DaiAiAutoConfiguration}.
 */
@NullMarked
@FunctionalInterface
public interface ProposalService {

    /**
     * What kind of change a proposing tool stands for; it decides the default approval requirement (a delete needs a
     * second person, LLD-11 §9).
     */
    enum Change {
        /** Creates a record. */
        CREATE,
        /** Changes a record. */
        UPDATE,
        /** Deletes a record. */
        DELETE
    }

    /**
     * One tool call that asks for a change.
     *
     * @param binding          the governing tool binding (workspace, source, change kind)
     * @param toolInput        the arguments to apply, after the binding's argument constraints (may contain personal
     *                         data — never log)
     * @param principal        the caller, who owns the proposal
     * @param scope            channel and turn or MCP request the call belongs to
     * @param toolInvocationId id the call is recorded under ({@code dai_tool_invocation}), so proposal and call refer
     *                         to each other
     */
    record ProposalRequest(ToolBinding binding, String toolInput, DaiPrincipal principal, ToolCallScope scope,
                           UUID toolInvocationId) {
        /** Validates the components. */
        public ProposalRequest {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(toolInput, "toolInput");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(toolInvocationId, "toolInvocationId");
        }
    }

    /**
     * The request cannot become a proposal, for a reason the model or user can act on. The message is shown to the
     * model, so it must be fixed text without personal data.
     */
    final class ProposalRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        /**
         * Creates the exception.
         *
         * @param code        stable error code (for example {@code writes_disabled})
         * @param safeMessage fixed, displayable text
         */
        public ProposalRefusedException(String code, String safeMessage) {
            super(Objects.requireNonNull(safeMessage, "safeMessage"), null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** @return stable error code */
        public String code() {
            return code;
        }
    }

    /**
     * Creates a change proposal for a mutating tool call. Repeating the same call within a turn (same binding and
     * arguments) returns the same proposal.
     *
     * @param request the call
     * @return the id of the stored proposal, ready for the review API
     * @throws ProposalRefusedException when the call cannot become a proposal
     */
    UUID createProposal(ProposalRequest request);
}
