package com.springaimcpservercommon.persistence.telemetry;

/** Lifecycle of a conversation ({@code ck_conversation_status}). */
public enum ConversationStatus {
    /** Accepting messages. */
    ACTIVE,
    /** Closed by the client or the runtime; kept until retention. */
    CLOSED,
    /** Content erased on request; the row remains as a tombstone until retention. */
    ERASED
}
