-- =====================================================================================================
-- Migration V8: trace-id lookup of MCP requests (OQ-50). Turns and audit events already have a partial trace index;
-- MCP requests did not, so following an external trace id to the requests it covers was a scan of every partition.
-- Created on the partitioned parent, so every existing and future monthly partition gets it.
-- =====================================================================================================

CREATE INDEX ix_mcp_request_trace ON dai_mcp_request (trace_id) WHERE trace_id IS NOT NULL;
