package com.springaimcpservercommon.persistence.usage;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Whose usage a budget limits and a usage total sums (LLD-10 §6). The variants mirror {@code ck_budget_scope}.
 */
public sealed interface BudgetTarget {

    /**
     * Scope name stored in {@code dai_budget.scope}.
     *
     * @return the scope
     */
    Scope scope();

    /** Stored scope values. */
    enum Scope {
        /** All usage. */
        GLOBAL,
        /** One workspace. */
        WORKSPACE,
        /** One agent (resource of kind AGENT) in a workspace. */
        AGENT,
        /** One principal, optionally within one workspace. */
        PRINCIPAL
    }

    /** All usage. */
    record Global() implements BudgetTarget {
        @Override
        public Scope scope() {
            return Scope.GLOBAL;
        }
    }

    /**
     * One workspace.
     *
     * @param workspaceId workspace
     */
    record Workspace(UUID workspaceId) implements BudgetTarget {
        /** Validates components. */
        public Workspace {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }

        @Override
        public Scope scope() {
            return Scope.WORKSPACE;
        }
    }

    /**
     * One agent.
     *
     * @param workspaceId     workspace of the agent
     * @param agentResourceId agent resource
     */
    record Agent(UUID workspaceId, UUID agentResourceId) implements BudgetTarget {
        /** Validates components. */
        public Agent {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(agentResourceId, "agentResourceId");
        }

        @Override
        public Scope scope() {
            return Scope.AGENT;
        }
    }

    /**
     * One principal.
     *
     * @param workspaceId optional workspace restriction ({@code null} = across workspaces)
     * @param principalId principal
     */
    record Principal(@Nullable UUID workspaceId, UUID principalId) implements BudgetTarget {
        /** Validates components. */
        public Principal {
            Objects.requireNonNull(principalId, "principalId");
        }

        @Override
        public Scope scope() {
            return Scope.PRINCIPAL;
        }
    }

    /**
     * Rebuilds a target from stored columns.
     *
     * @param scope           stored scope
     * @param workspaceId     workspace column
     * @param agentResourceId agent column
     * @param principalId     principal column
     * @return the target
     */
    static BudgetTarget of(Scope scope, @Nullable UUID workspaceId, @Nullable UUID agentResourceId,
                           @Nullable UUID principalId) {
        return switch (scope) {
            case GLOBAL -> new Global();
            case WORKSPACE -> new Workspace(Objects.requireNonNull(workspaceId, "workspaceId"));
            case AGENT -> new Agent(Objects.requireNonNull(workspaceId, "workspaceId"),
                    Objects.requireNonNull(agentResourceId, "agentResourceId"));
            case PRINCIPAL -> new Principal(workspaceId, Objects.requireNonNull(principalId, "principalId"));
        };
    }
}
