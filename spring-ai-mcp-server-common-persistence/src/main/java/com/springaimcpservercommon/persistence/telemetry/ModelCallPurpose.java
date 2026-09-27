package com.springaimcpservercommon.persistence.telemetry;

/** Why a model was called ({@code ck_model_call_purpose}); AGENT_TURN calls need a turn id. */
public enum ModelCallPurpose {
    /** Part of an agent turn. */
    AGENT_TURN,
    /** Conversation memory summarisation. */
    MEMORY_SUMMARY,
    /** Evaluation run. */
    EVALUATION,
    /** Model routing decision. */
    ROUTING,
    /** Embedding computation. */
    EMBEDDING
}
