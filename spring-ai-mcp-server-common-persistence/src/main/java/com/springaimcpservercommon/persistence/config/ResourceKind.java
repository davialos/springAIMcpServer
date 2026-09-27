package com.springaimcpservercommon.persistence.config;

/** Kind of configurable resource (matches {@code ck_resource_kind}). */
public enum ResourceKind {
    /** Dynamic REST endpoint (LLD-04). */
    ENDPOINT,
    /** Dynamic query (LLD-05). */
    QUERY,
    /** Agent definition (LLD-06). */
    AGENT,
    /** Tool binding of an agent (LLD-07). */
    TOOL_BINDING,
    /** Row-level policy. */
    ROW_POLICY,
    /** Catalog policy overlay (LLD-03). */
    POLICY_OVERLAY,
    /** Exposed MCP server definition. */
    MCP_SERVER
}
