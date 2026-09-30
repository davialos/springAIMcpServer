package com.springaimcpservercommon.ai.runtime;

import org.jspecify.annotations.NullMarked;

import java.util.UUID;

/**
 * The key under which a conversation's model memory is stored. One definition, used by the invoker (to read and write
 * memory) and by conversation erasure (to delete it), so the two can never drift apart.
 */
@NullMarked
public final class ConversationKeys {

    /** Prefix of the key under which {@code SummaryMemoryAdvisor} keeps the running summary. */
    public static final String SUMMARY_PREFIX = "dai:sum:";

    private ConversationKeys() {
    }

    /**
     * Memory key of a conversation; it embeds workspace, agent and principal so a guessed conversation id alone never
     * reaches another user's memory.
     *
     * @param workspaceId    workspace of the agent
     * @param agentId        agent resource id
     * @param principalId    caller
     * @param conversationId conversation id chosen by the client
     * @return the key
     */
    public static String memoryKey(UUID workspaceId, UUID agentId, UUID principalId, UUID conversationId) {
        return workspaceId + ":" + agentId + ":" + principalId + ":" + conversationId;
    }

    /**
     * Key of the running summary kept next to the memory (summary strategy).
     *
     * @param memoryKey key from {@link #memoryKey}
     * @return the summary key
     */
    public static String summaryKey(String memoryKey) {
        return SUMMARY_PREFIX + memoryKey;
    }
}
