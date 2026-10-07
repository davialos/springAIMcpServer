package com.springaimcpservercommon.ecosystem.ruleengine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the AI assistant (ADR-0028).
 *
 * @param enabled   whether the console may open the assistant at all
 * @param provider  provider id of the agent's model, matched to a {@code ChatModel} bean by the library's router:
 *                  {@code offline} (the built-in template assistant, the default so the stack works without a key) or
 *                  {@code anthropic} (needs {@code SPRING_AI_MODEL_CHAT=anthropic} and {@code ANTHROPIC_API_KEY})
 * @param modelName model name sent to the provider
 */
@ConfigurationProperties("ecosystem.assistant")
public record AssistantProperties(@DefaultValue("true") boolean enabled,
                                  @DefaultValue("offline") String provider,
                                  @DefaultValue("offline-rule-assistant") String modelName) {
}
