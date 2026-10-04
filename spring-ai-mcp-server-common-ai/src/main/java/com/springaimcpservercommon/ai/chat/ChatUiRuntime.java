package com.springaimcpservercommon.ai.chat;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.ChatUiSpec;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The chat-interface collaborators of the agent runtime: the host's default {@link ChatUiSpec}
 * ({@code dynamic.ai.agent.chat.ui.*}) and the {@link ChatUiState} that keeps components and answers.
 *
 * @param defaults features for agents whose spec has no {@code output.ui}; {@code null} = none (plain text stream,
 *                 the behaviour before the chat UI existed)
 * @param state    where shown components, answers and feedback are kept
 */
public record ChatUiRuntime(@Nullable ChatUiSpec defaults, ChatUiState state) {

    /** No chat-interface features unless an agent asks for them; nothing kept. */
    public static final ChatUiRuntime OFF = new ChatUiRuntime(null, ChatUiState.NONE);

    /** Validates the components. */
    public ChatUiRuntime {
        Objects.requireNonNull(state, "state");
    }

    /**
     * The features of an agent's turns: its own {@code output.ui}, else the host defaults.
     *
     * @param agent the agent
     * @return the features, or {@code null} when none apply
     */
    public @Nullable ChatUiSpec effective(AgentDefinition agent) {
        ChatUiSpec own = agent.output().ui();
        return own != null ? own : defaults;
    }
}
