package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.mockdata.ai.AiMockGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

/** Creates the Spring AI adapter. Kept apart so the auto-configuration loads without Spring AI on the class path. */
final class SpringAiMockSupport {

    private SpringAiMockSupport() {
    }

    /**
     * The adapter over the host's {@code ChatClient.Builder} (preferred) or its single {@code ChatModel}.
     *
     * @param context the application context
     * @param timeout timeout of one model call
     * @return the adapter, or {@code null} when the host has no model
     */
    static @Nullable AiMockGenerator create(ApplicationContext context, Duration timeout) {
        ChatClient.Builder builder = context.getBeanProvider(ChatClient.Builder.class).getIfAvailable();
        if (builder != null) {
            return new SpringAiMockGenerator(builder.build(), timeout);
        }
        ChatModel model = context.getBeanProvider(ChatModel.class).getIfUnique();
        return model == null ? null : new SpringAiMockGenerator(ChatClient.create(model), timeout);
    }
}
