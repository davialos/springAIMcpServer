package com.springaimcpservercommon.ai.runtime;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

class ChatOptionsMappingTest {

    @Test
    void theAgentsModelNameTemperatureAndTokenLimitBecomeRequestOptions() {
        ChatOptions options = DefaultAgentInvoker.chatOptions(
                new ModelSelection("openai", "gpt-x", 0.2, 800, null)).build();

        assertThat(options.getModel()).isEqualTo("gpt-x");
        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(options.getMaxTokens()).isEqualTo(800);
    }

    @Test
    void unsetTemperatureAndTokenLimitLeaveTheProvidersDefaults() {
        ChatOptions options = DefaultAgentInvoker.chatOptions(
                new ModelSelection("openai", "gpt-x", null, null, null)).build();

        assertThat(options.getModel()).isEqualTo("gpt-x");
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getMaxTokens()).isNull();
    }
}
