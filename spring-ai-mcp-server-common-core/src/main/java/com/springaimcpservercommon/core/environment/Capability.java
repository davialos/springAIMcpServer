package com.springaimcpservercommon.core.environment;

/**
 * Capabilities gated by the environment (LLD-12 §2.2).
 */
public enum Capability {
    /** Published endpoints and agents (read). Available in every tier. */
    DATA_PLANE(false),
    /** Reviewed writes (LLD-11), if {@code write.enabled}. Available in every tier. */
    REVIEWED_WRITES(false),
    /** MCP server (if enabled). Available in every tier. */
    MCP_SERVER(false),
    /** Usage, audit, kill switches, cluster status. Available in every tier. */
    OPS_VIEWS(false),
    /** Drafts and endpoint/query/agent builders. Off in PROD unless overridden. */
    AUTHORING(true),
    /** Catalog browser, entity graph, JSON schemas. Off in PROD unless overridden. */
    INTROSPECTION(true),
    /** Query preview / explain / generated SQL. Never in PROD, not even with an override. */
    QUERY_PREVIEW(true),
    /** Playground against live data. Off in PROD unless overridden. */
    PLAYGROUND(true),
    /** Config changes through the UI; PROD accepts signed bundles only unless overridden. */
    CONFIG_CHANGES_UI(true);

    private final boolean restrictedInProduction;

    Capability(boolean restrictedInProduction) {
        this.restrictedInProduction = restrictedInProduction;
    }

    /**
     * Whether the capability is off by default under production rules.
     *
     * @return {@code true} for authoring-type capabilities
     */
    public boolean restrictedInProduction() {
        return restrictedInProduction;
    }

    /**
     * Whether a production override may enable the capability.
     *
     * @return {@code false} only for {@link #QUERY_PREVIEW}
     */
    public boolean overridableInProduction() {
        return this != QUERY_PREVIEW;
    }
}
