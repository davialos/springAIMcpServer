-- =====================================================================================================
-- Migration V3: conversations, agent turns, model (LLM) calls, MCP sessions/requests, tool invocations.
-- Design: docs/lld/15-database-schema.md §4, LLD-06, LLD-07, LLD-10.
--
-- High-volume tables are RANGE-partitioned by month on their time column (retention = DROP PARTITION).
-- Their primary key includes the partition column (PostgreSQL requirement). Rows are written once, when the
-- call completes (append-only telemetry) — so no UPDATE ever has to locate a row without the partition key.
-- References *between* partitioned tables are logical (indexed, not FK-enforced) to keep inserts cheap and
-- partitions independently droppable; references to small reference tables (principal, workspace) are enforced.
-- Channel values are shared by all tables: CHAT (agent chat API), PLAYGROUND, MCP, ENDPOINT (dynamic endpoint).
-- =====================================================================================================

-- ---------------------------------------------------------------------------------------------------
-- Conversations and messages (chat memory, LLD-06 §7). Retention by retention_until (purge job), erasure on request.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_conversation
(
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    workspace_id          uuid        NOT NULL,
    agent_resource_id     uuid,
    principal_id          uuid        NOT NULL,
    channel               text        NOT NULL,
    conversation_key_hash text        NOT NULL,
    title                 text,
    status                text        NOT NULL DEFAULT 'ACTIVE',
    started_at            timestamptz NOT NULL DEFAULT now(),
    last_activity_at      timestamptz NOT NULL DEFAULT now(),
    retention_until       timestamptz NOT NULL,
    erased_at             timestamptz,
    row_version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_conversation PRIMARY KEY (id),
    CONSTRAINT uq_conversation_key UNIQUE (conversation_key_hash),
    CONSTRAINT ck_conversation_channel CHECK (channel IN ('CHAT', 'PLAYGROUND', 'MCP', 'ENDPOINT')),
    CONSTRAINT ck_conversation_status CHECK (status IN ('ACTIVE', 'CLOSED', 'ERASED')),
    CONSTRAINT ck_conversation_erasure CHECK ((status = 'ERASED') = (erased_at IS NOT NULL)),
    CONSTRAINT fk_conversation_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_conversation_agent FOREIGN KEY (agent_resource_id) REFERENCES dai_resource (id),
    CONSTRAINT fk_conversation_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id)
);
CREATE INDEX ix_conversation_principal ON dai_conversation (principal_id, last_activity_at DESC);
CREATE INDEX ix_conversation_agent ON dai_conversation (agent_resource_id, last_activity_at DESC) WHERE agent_resource_id IS NOT NULL;
CREATE INDEX ix_conversation_retention ON dai_conversation (retention_until) WHERE status <> 'ERASED';
COMMENT ON COLUMN dai_conversation.conversation_key_hash IS 'SHA-256 of (principal, agent, client session): a guessed conversation id alone never grants access (LLD-06 §7).';

CREATE TABLE dai_conversation_message
(
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    conversation_id uuid        NOT NULL,
    seq             integer     NOT NULL,
    role            text        NOT NULL,
    content         text        NOT NULL,
    redacted        boolean     NOT NULL DEFAULT false,
    turn_id         uuid,
    tool_call_id    text,
    token_count     integer,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_conversation_message PRIMARY KEY (id),
    CONSTRAINT uq_conversation_message_seq UNIQUE (conversation_id, seq),
    CONSTRAINT ck_conversation_message_seq CHECK (seq >= 0),
    CONSTRAINT ck_conversation_message_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM')),
    CONSTRAINT ck_conversation_message_tool CHECK (role <> 'TOOL' OR tool_call_id IS NOT NULL),
    CONSTRAINT fk_conversation_message_conversation FOREIGN KEY (conversation_id) REFERENCES dai_conversation (id) ON DELETE CASCADE
);
COMMENT ON COLUMN dai_conversation_message.content IS 'Stored after redaction; tool results are stored masked (LLD-06 §7).';

-- ---------------------------------------------------------------------------------------------------
-- Agent turns: one user message → final answer (may contain several model calls and tool calls)
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_agent_turn
(
    id                     uuid        NOT NULL,
    started_at             timestamptz NOT NULL,
    ended_at               timestamptz NOT NULL,
    conversation_id        uuid,
    workspace_id           uuid        NOT NULL,
    agent_resource_id      uuid,
    agent_revision_id      uuid,
    principal_id           uuid        NOT NULL,
    channel                text        NOT NULL,
    trace_id               text,
    client_request_id      text,
    finish_reason          text        NOT NULL,
    outcome                text        NOT NULL,
    error_code             text,
    time_to_first_token_ms integer,
    CONSTRAINT pk_agent_turn PRIMARY KEY (id, started_at),
    CONSTRAINT ck_agent_turn_channel CHECK (channel IN ('CHAT', 'PLAYGROUND', 'MCP', 'ENDPOINT')),
    CONSTRAINT ck_agent_turn_finish CHECK (finish_reason IN ('STOP', 'LENGTH', 'TOOL_LIMIT', 'BUDGET', 'CANCELLED', 'ERROR')),
    CONSTRAINT ck_agent_turn_outcome CHECK (outcome IN ('SUCCESS', 'FAILED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_agent_turn_error CHECK (outcome = 'SUCCESS' OR error_code IS NOT NULL),
    CONSTRAINT ck_agent_turn_times CHECK (ended_at >= started_at),
    CONSTRAINT fk_agent_turn_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_agent_turn_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id)
) PARTITION BY RANGE (started_at);
CREATE INDEX ix_agent_turn_workspace ON dai_agent_turn (workspace_id, started_at DESC);
CREATE INDEX ix_agent_turn_principal ON dai_agent_turn (principal_id, started_at DESC);
CREATE INDEX ix_agent_turn_agent ON dai_agent_turn (agent_resource_id, started_at DESC) WHERE agent_resource_id IS NOT NULL;
CREATE INDEX ix_agent_turn_conversation ON dai_agent_turn (conversation_id) WHERE conversation_id IS NOT NULL;
CREATE INDEX ix_agent_turn_trace ON dai_agent_turn (trace_id) WHERE trace_id IS NOT NULL;
CREATE TABLE dai_agent_turn_pdefault PARTITION OF dai_agent_turn DEFAULT;

-- ---------------------------------------------------------------------------------------------------
-- Model calls ("AI calls"): every request to an LLM provider, with tokens, latency, cost and outcome
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_model_call
(
    id                     uuid        NOT NULL,
    started_at             timestamptz NOT NULL,
    ended_at               timestamptz NOT NULL,
    turn_id                uuid,
    seq                    smallint,
    workspace_id           uuid        NOT NULL,
    purpose                text        NOT NULL,
    provider               text        NOT NULL,
    model                  text        NOT NULL,
    streaming              boolean     NOT NULL,
    time_to_first_token_ms integer,
    input_tokens           integer     NOT NULL DEFAULT 0,
    output_tokens          integer     NOT NULL DEFAULT 0,
    cached_input_tokens    integer     NOT NULL DEFAULT 0,
    cost_micros            bigint      NOT NULL DEFAULT 0,
    currency               char(3),
    finish_reason          text,
    outcome                text        NOT NULL,
    error_code             text,
    provider_request_id    text,
    fallback_of_id         uuid,
    CONSTRAINT pk_model_call PRIMARY KEY (id, started_at),
    CONSTRAINT ck_model_call_purpose CHECK (purpose IN ('AGENT_TURN', 'MEMORY_SUMMARY', 'EVALUATION', 'ROUTING', 'EMBEDDING')),
    CONSTRAINT ck_model_call_turn CHECK (purpose <> 'AGENT_TURN' OR turn_id IS NOT NULL),
    CONSTRAINT ck_model_call_outcome CHECK (outcome IN ('SUCCESS', 'ERROR', 'TIMEOUT', 'CANCELLED', 'CIRCUIT_OPEN', 'RATE_LIMITED')),
    CONSTRAINT ck_model_call_tokens CHECK (input_tokens >= 0 AND output_tokens >= 0 AND cached_input_tokens >= 0 AND cost_micros >= 0),
    CONSTRAINT ck_model_call_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_model_call_times CHECK (ended_at >= started_at),
    CONSTRAINT fk_model_call_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id)
) PARTITION BY RANGE (started_at);
CREATE INDEX ix_model_call_turn ON dai_model_call (turn_id) WHERE turn_id IS NOT NULL;
CREATE INDEX ix_model_call_workspace ON dai_model_call (workspace_id, started_at DESC);
CREATE INDEX ix_model_call_model ON dai_model_call (provider, model, started_at DESC);
CREATE INDEX ix_model_call_failures ON dai_model_call (started_at DESC) WHERE outcome <> 'SUCCESS';
CREATE TABLE dai_model_call_pdefault PARTITION OF dai_model_call DEFAULT;

-- ---------------------------------------------------------------------------------------------------
-- MCP sessions (stateful Streamable HTTP) and requests (every JSON-RPC call, stateful or stateless)
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_mcp_session
(
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    session_id_hash  text        NOT NULL,
    mcp_client_id    uuid,
    workspace_id     uuid,
    principal_id     uuid        NOT NULL,
    transport        text        NOT NULL,
    protocol_version text,
    client_name      text,
    client_version   text,
    started_at       timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    ended_at         timestamptz,
    end_reason       text,
    row_version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mcp_session PRIMARY KEY (id),
    CONSTRAINT uq_mcp_session_hash UNIQUE (session_id_hash),
    CONSTRAINT ck_mcp_session_transport CHECK (transport IN ('STREAMABLE_HTTP', 'STATELESS', 'SSE')),
    CONSTRAINT ck_mcp_session_end CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
    CONSTRAINT ck_mcp_session_end_reason CHECK (end_reason IS NULL OR end_reason IN ('CLIENT_CLOSED', 'IDLE_TIMEOUT', 'TOKEN_EXPIRED',
                                                                                     'REVOKED', 'SERVER_SHUTDOWN', 'ERROR')),
    CONSTRAINT fk_mcp_session_client FOREIGN KEY (mcp_client_id) REFERENCES dai_mcp_client (id),
    CONSTRAINT fk_mcp_session_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_mcp_session_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id)
);
CREATE INDEX ix_mcp_session_principal ON dai_mcp_session (principal_id, started_at DESC);
CREATE INDEX ix_mcp_session_open ON dai_mcp_session (last_seen_at) WHERE ended_at IS NULL;
COMMENT ON COLUMN dai_mcp_session.session_id_hash IS 'SHA-256 of Mcp-Session-Id; the raw session id is never stored.';

CREATE TABLE dai_mcp_request
(
    id             uuid        NOT NULL,
    received_at    timestamptz NOT NULL,
    completed_at   timestamptz NOT NULL,
    mcp_session_id uuid,
    mcp_client_id  uuid,
    principal_id   uuid        NOT NULL,
    workspace_id   uuid,
    jsonrpc_method text        NOT NULL,
    jsonrpc_id     text,
    tool_name      text,
    status         text        NOT NULL,
    error_code     text,
    trace_id       text,
    CONSTRAINT pk_mcp_request PRIMARY KEY (id, received_at),
    CONSTRAINT ck_mcp_request_status CHECK (status IN ('OK', 'ERROR', 'DENIED', 'RATE_LIMITED')),
    CONSTRAINT ck_mcp_request_method CHECK (jsonrpc_method ~ '^[a-z][A-Za-z/_]{1,63}$'),
    CONSTRAINT ck_mcp_request_tool CHECK (tool_name IS NULL OR tool_name ~ '^[a-z][a-z0-9_]{2,63}$'),
    CONSTRAINT ck_mcp_request_times CHECK (completed_at >= received_at),
    CONSTRAINT fk_mcp_request_client FOREIGN KEY (mcp_client_id) REFERENCES dai_mcp_client (id),
    CONSTRAINT fk_mcp_request_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id),
    CONSTRAINT fk_mcp_request_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id)
) PARTITION BY RANGE (received_at);
CREATE INDEX ix_mcp_request_session ON dai_mcp_request (mcp_session_id, received_at) WHERE mcp_session_id IS NOT NULL;
CREATE INDEX ix_mcp_request_principal ON dai_mcp_request (principal_id, received_at DESC);
CREATE INDEX ix_mcp_request_denied ON dai_mcp_request (received_at DESC) WHERE status IN ('DENIED', 'RATE_LIMITED');
CREATE TABLE dai_mcp_request_pdefault PARTITION OF dai_mcp_request DEFAULT;

-- ---------------------------------------------------------------------------------------------------
-- Tool invocations: every action executed or proposed for a user via AI assistance or MCP directly
-- (the central "what did the AI do on whose behalf" record; READ = executed read, PROPOSE = write proposal)
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE dai_tool_invocation
(
    id                    uuid        NOT NULL,
    started_at            timestamptz NOT NULL,
    ended_at              timestamptz NOT NULL,
    channel               text        NOT NULL,
    turn_id               uuid,
    model_call_id         uuid,
    provider_tool_call_id text,
    mcp_request_id        uuid,
    workspace_id          uuid        NOT NULL,
    principal_id          uuid        NOT NULL,
    tool_name             text        NOT NULL,
    element_ref           text        NOT NULL,
    binding_revision_id   uuid,
    access_mode           text        NOT NULL,
    args_hash             text        NOT NULL,
    args_redacted         jsonb,
    status                text        NOT NULL,
    row_count             integer,
    result_hash           text,
    truncated             boolean     NOT NULL DEFAULT false,
    error_code            text,
    write_violation       boolean     NOT NULL DEFAULT false,
    proposal_id           uuid,
    CONSTRAINT pk_tool_invocation PRIMARY KEY (id, started_at),
    CONSTRAINT ck_tool_invocation_channel CHECK (channel IN ('CHAT', 'PLAYGROUND', 'MCP', 'ENDPOINT')),
    CONSTRAINT ck_tool_invocation_origin CHECK (
        (channel IN ('CHAT', 'PLAYGROUND') AND turn_id IS NOT NULL)
            OR (channel = 'MCP' AND mcp_request_id IS NOT NULL)
            OR channel = 'ENDPOINT'),
    CONSTRAINT ck_tool_invocation_name CHECK (tool_name ~ '^[a-z][a-z0-9_]{2,63}$'),
    CONSTRAINT ck_tool_invocation_ref CHECK (element_ref ~ '^(op|entity|query|agent|mcp):'),
    CONSTRAINT ck_tool_invocation_mode CHECK (access_mode IN ('READ', 'PROPOSE')),
    CONSTRAINT ck_tool_invocation_status CHECK (status IN ('OK', 'EMPTY', 'TRUNCATED', 'ERROR', 'NOT_PERMITTED',
                                                           'UNAVAILABLE', 'PROPOSED', 'TIMEOUT')),
    CONSTRAINT ck_tool_invocation_proposal CHECK ((status = 'PROPOSED') = (proposal_id IS NOT NULL)),
    CONSTRAINT ck_tool_invocation_propose_mode CHECK (status <> 'PROPOSED' OR access_mode = 'PROPOSE'),
    CONSTRAINT ck_tool_invocation_args_hash CHECK (args_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_tool_invocation_result_hash CHECK (result_hash IS NULL OR result_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_tool_invocation_times CHECK (ended_at >= started_at),
    CONSTRAINT fk_tool_invocation_workspace FOREIGN KEY (workspace_id) REFERENCES dai_workspace (id),
    CONSTRAINT fk_tool_invocation_principal FOREIGN KEY (principal_id) REFERENCES dai_principal (id),
    CONSTRAINT fk_tool_invocation_binding FOREIGN KEY (binding_revision_id) REFERENCES dai_resource_revision (id)
) PARTITION BY RANGE (started_at);
CREATE INDEX ix_tool_invocation_turn ON dai_tool_invocation (turn_id) WHERE turn_id IS NOT NULL;
CREATE INDEX ix_tool_invocation_mcp_request ON dai_tool_invocation (mcp_request_id) WHERE mcp_request_id IS NOT NULL;
CREATE INDEX ix_tool_invocation_principal ON dai_tool_invocation (principal_id, started_at DESC);
CREATE INDEX ix_tool_invocation_tool ON dai_tool_invocation (workspace_id, tool_name, started_at DESC);
CREATE INDEX ix_tool_invocation_violation ON dai_tool_invocation (started_at DESC) WHERE write_violation;
CREATE INDEX ix_tool_invocation_proposal ON dai_tool_invocation (proposal_id) WHERE proposal_id IS NOT NULL;
CREATE TABLE dai_tool_invocation_pdefault PARTITION OF dai_tool_invocation DEFAULT;

-- ---------------------------------------------------------------------------------------------------
-- Usage per turn, derived from model calls (single owner of token facts: dai_model_call)
-- ---------------------------------------------------------------------------------------------------
CREATE VIEW dai_v_turn_usage AS
SELECT t.id                                   AS turn_id,
       t.started_at,
       t.workspace_id,
       t.agent_resource_id,
       t.principal_id,
       t.channel,
       t.outcome,
       count(mc.id)                           AS model_calls,
       coalesce(sum(mc.input_tokens), 0)      AS input_tokens,
       coalesce(sum(mc.output_tokens), 0)     AS output_tokens,
       coalesce(sum(mc.cached_input_tokens), 0) AS cached_input_tokens,
       coalesce(sum(mc.cost_micros), 0)       AS cost_micros
  FROM dai_agent_turn t
  LEFT JOIN dai_model_call mc ON mc.turn_id = t.id
 GROUP BY t.id, t.started_at, t.workspace_id, t.agent_resource_id, t.principal_id, t.channel, t.outcome;
