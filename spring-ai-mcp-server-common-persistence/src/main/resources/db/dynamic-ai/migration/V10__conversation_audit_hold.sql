-- =====================================================================================================
-- Migration V10: keep erased conversations for audit (LLD-06 §7).
-- A user's erase can hide a conversation (from the user and the model) while its redacted transcript is kept until
-- audit_hold_until for audit and analysis; retention_until is set to the same instant, so the existing purge job
-- deletes it then. NULL = no hold (a hard erase deletes the messages at once).
-- =====================================================================================================

ALTER TABLE dai_conversation ADD COLUMN audit_hold_until timestamptz;
ALTER TABLE dai_conversation ADD CONSTRAINT ck_conversation_audit_hold
    CHECK (audit_hold_until IS NULL OR status = 'ERASED');
CREATE INDEX ix_conversation_workspace ON dai_conversation (workspace_id, last_activity_at DESC);
COMMENT ON COLUMN dai_conversation.audit_hold_until IS 'Erased by the user but kept for audit until this instant (then purged); NULL when not held.';
