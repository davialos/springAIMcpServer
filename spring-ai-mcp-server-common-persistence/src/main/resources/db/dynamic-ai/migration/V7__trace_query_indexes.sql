-- =====================================================================================================
-- Migration V7: indexes for the trace viewer (admin trace API, docs/lld/15-database-schema.md §4).
--   * MCP requests of a workspace in a time window (there was only a per-principal and a per-session index);
--   * tool invocations that did not succeed, per workspace ("what is failing or being denied").
-- Created on the partitioned parents, so every existing and future monthly partition gets them.
-- =====================================================================================================

CREATE INDEX ix_mcp_request_workspace ON dai_mcp_request (workspace_id, received_at DESC)
    WHERE workspace_id IS NOT NULL;

CREATE INDEX ix_tool_invocation_problems ON dai_tool_invocation (workspace_id, started_at DESC)
    WHERE status <> 'OK' AND status <> 'EMPTY';
