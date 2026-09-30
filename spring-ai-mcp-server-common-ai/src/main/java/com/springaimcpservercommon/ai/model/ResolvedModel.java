package com.springaimcpservercommon.ai.model;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Objects;

/**
 * The outcome of routing: the {@link ChatModel} to call and the {@link ModelSelection} that actually matched it.
 * When the primary provider was unavailable this is the fallback selection, so model name, temperature and token
 * limit are those configured for the provider that will really be called (never the primary's model name sent to a
 * different provider).
 *
 * @param model     the chat model to call
 * @param selection the selection that resolved to {@code model}
 */
@NullMarked
public record ResolvedModel(ChatModel model, ModelSelection selection) {

    /** Validates the components. */
    public ResolvedModel {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(selection, "selection");
    }
}
