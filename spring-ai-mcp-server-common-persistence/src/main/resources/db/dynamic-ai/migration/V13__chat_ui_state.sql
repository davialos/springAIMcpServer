-- =====================================================================================================
-- Migration V13: interactive chat state (LLD-13 §3, F-53).
-- An embeddable chat shows components the user acts on (choices) and collects like/dislike feedback per answer.
-- Both must survive a reload and be visible on every replica (ADR-0021), and an answer must be checked against what
-- was actually shown. Rows are keyed by conversation_key = sha256 of (workspace, agent, principal, conversation), so a
-- guessed conversation id never reaches another user's state; workspace/agent/principal are kept for reporting.
-- No foreign keys (like dai_chat_memory_message): rows are written from the turn path and expire on their own clock.
-- =====================================================================================================

CREATE TABLE dai_chat_interaction
(
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    conversation_key text        NOT NULL,
    workspace_id     uuid        NOT NULL,
    agent_id         uuid        NOT NULL,
    principal_id     uuid        NOT NULL,
    turn_id          uuid        NOT NULL,
    component_id     text        NOT NULL,
    component_type   text        NOT NULL,
    payload          jsonb       NOT NULL,
    answer           jsonb,
    created_at       timestamptz NOT NULL,
    answered_at      timestamptz,
    expires_at       timestamptz NOT NULL,
    CONSTRAINT pk_chat_interaction PRIMARY KEY (id),
    CONSTRAINT uq_chat_interaction_component UNIQUE (conversation_key, turn_id, component_id),
    CONSTRAINT ck_chat_interaction_key CHECK (conversation_key ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_chat_interaction_component CHECK (component_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT ck_chat_interaction_type CHECK (component_type ~ '^[a-z][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_chat_interaction_answer CHECK ((answer IS NULL) = (answered_at IS NULL)),
    CONSTRAINT ck_chat_interaction_times CHECK (expires_at > created_at)
);
CREATE INDEX ix_chat_interaction_conversation ON dai_chat_interaction (conversation_key, created_at);
CREATE INDEX ix_chat_interaction_expiry ON dai_chat_interaction (expires_at);
COMMENT ON TABLE dai_chat_interaction IS 'Interactive components shown in a chat (e.g. choice) and the user''s answer; restored on reload.';
COMMENT ON COLUMN dai_chat_interaction.payload IS 'The component as shown to the user, after PII redaction.';
COMMENT ON COLUMN dai_chat_interaction.answer IS 'Validated answer {values, labels, other?}; written once.';

CREATE TABLE dai_turn_feedback
(
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    conversation_key text        NOT NULL,
    workspace_id     uuid        NOT NULL,
    agent_id         uuid        NOT NULL,
    principal_id     uuid        NOT NULL,
    turn_id          uuid        NOT NULL,
    rating           text        NOT NULL,
    reason           text,
    comment          text,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    CONSTRAINT pk_turn_feedback PRIMARY KEY (id),
    CONSTRAINT uq_turn_feedback_turn UNIQUE (conversation_key, turn_id),
    CONSTRAINT ck_turn_feedback_key CHECK (conversation_key ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_turn_feedback_rating CHECK (rating IN ('UP', 'DOWN')),
    CONSTRAINT ck_turn_feedback_reason CHECK (reason IS NULL OR reason ~ '^[a-z][a-z0-9_]{0,39}$'),
    CONSTRAINT ck_turn_feedback_comment CHECK (comment IS NULL OR length(comment) <= 2000),
    CONSTRAINT ck_turn_feedback_times CHECK (updated_at >= created_at AND expires_at > updated_at)
);
CREATE INDEX ix_turn_feedback_conversation ON dai_turn_feedback (conversation_key);
CREATE INDEX ix_turn_feedback_agent ON dai_turn_feedback (workspace_id, agent_id, updated_at DESC);
CREATE INDEX ix_turn_feedback_expiry ON dai_turn_feedback (expires_at);
COMMENT ON TABLE dai_turn_feedback IS 'Like/dislike per agent answer; one row per (conversation, turn), replaced when the user changes it.';
COMMENT ON COLUMN dai_turn_feedback.comment IS 'Optional free text, stored after PII redaction.';
