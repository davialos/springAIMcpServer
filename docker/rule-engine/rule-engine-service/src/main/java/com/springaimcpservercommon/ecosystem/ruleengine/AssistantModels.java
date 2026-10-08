package com.springaimcpservercommon.ecosystem.ruleengine;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The chat models an agent of this service can use. The library's router picks a {@code ChatModel} bean whose name,
 * without the {@code ChatModel} suffix, equals the agent's provider id (no silent substitution to another provider):
 * {@code offlineChatModel} is always here, {@code anthropicChatModel} appears when the Anthropic starter is switched on
 * ({@code SPRING_AI_MODEL_CHAT=anthropic}, {@code ANTHROPIC_API_KEY}).
 */
@Configuration
class AssistantModels {

    @Bean(name = "offlineChatModel")
    ChatModel offlineChatModel() {
        return new OfflineChatModel();
    }
}
