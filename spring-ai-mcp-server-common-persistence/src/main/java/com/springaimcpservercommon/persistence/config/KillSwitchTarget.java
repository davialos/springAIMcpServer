package com.springaimcpservercommon.persistence.config;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a kill switch disables (F-73). The variants mirror {@code ck_kill_switch_scope}.
 */
public sealed interface KillSwitchTarget {

    /**
     * Scope name stored in {@code dai_kill_switch.scope}.
     *
     * @return the scope
     */
    Scope scope();

    /** Stored scope values. */
    enum Scope {
        /** Everything the library serves. */
        GLOBAL,
        /** One workspace. */
        WORKSPACE,
        /** One resource. */
        RESOURCE,
        /** One tool name (optionally within a workspace). */
        TOOL
    }

    /** Everything. */
    record Global() implements KillSwitchTarget {
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
    record Workspace(UUID workspaceId) implements KillSwitchTarget {
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
     * One resource.
     *
     * @param workspaceId optional workspace of the resource (for filtering)
     * @param resourceId  resource
     */
    record Resource(@Nullable UUID workspaceId, UUID resourceId) implements KillSwitchTarget {
        /** Validates components. */
        public Resource {
            Objects.requireNonNull(resourceId, "resourceId");
        }

        @Override
        public Scope scope() {
            return Scope.RESOURCE;
        }
    }

    /**
     * One tool name.
     *
     * @param workspaceId optional workspace restriction ({@code null} = in every workspace)
     * @param toolName    tool name ({@code [a-z][a-z0-9_]{2,63}})
     */
    record Tool(@Nullable UUID workspaceId, String toolName) implements KillSwitchTarget {

        private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{2,63}");

        /** Validates components. */
        public Tool {
            Objects.requireNonNull(toolName, "toolName");
            if (!TOOL_NAME.matcher(toolName).matches()) {
                throw new IllegalArgumentException("tool name must match [a-z][a-z0-9_]{2,63}: " + toolName);
            }
        }

        @Override
        public Scope scope() {
            return Scope.TOOL;
        }
    }
}
