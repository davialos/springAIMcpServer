-- =====================================================================================================
-- Migration V9: model chat memory shared by all replicas (OQ-45, ADR-0021).
-- The default Spring AI memory lives in one node's heap, so a follow-up served by another replica (or after a
-- restart) lost the conversation. This table holds the window the model reads, keyed by the SHA-256 of the
-- (workspace, agent, principal, conversation) key: a guessed conversation id alone never reaches another user's
-- memory. It is separate from dai_conversation_message, which is the user-visible transcript (optional, redacted,
-- retained per LLD-06 §7); memory is what the model is shown, redacted the same way, and expires on its own clock.
-- =====================================================================================================

CREATE TABLE dai_chat_memory_message
(
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    memory_key text        NOT NULL,
    seq        integer     NOT NULL,
    role       text        NOT NULL,
    content    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT pk_chat_memory_message PRIMARY KEY (id),
    CONSTRAINT uq_chat_memory_message_seq UNIQUE (memory_key, seq),
    CONSTRAINT ck_chat_memory_message_seq CHECK (seq >= 0),
    CONSTRAINT ck_chat_memory_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_chat_memory_message_key CHECK (memory_key ~ '^sha256:[0-9a-f]{64}$')
);
CREATE INDEX ix_chat_memory_message_expiry ON dai_chat_memory_message (expires_at);
COMMENT ON COLUMN dai_chat_memory_message.memory_key IS 'sha256:<hex> of the workspace:agent:principal:conversation key.';
COMMENT ON COLUMN dai_chat_memory_message.content IS 'Stored after redaction; expires_at slides with each write.';
