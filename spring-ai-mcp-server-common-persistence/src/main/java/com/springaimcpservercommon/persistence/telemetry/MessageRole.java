package com.springaimcpservercommon.persistence.telemetry;

/** Role of a conversation message ({@code ck_conversation_message_role}). */
public enum MessageRole {
    /** End-user input. */
    USER,
    /** Model output. */
    ASSISTANT,
    /** Tool result (requires a tool call id). */
    TOOL,
    /** System prompt or summary. */
    SYSTEM
}
