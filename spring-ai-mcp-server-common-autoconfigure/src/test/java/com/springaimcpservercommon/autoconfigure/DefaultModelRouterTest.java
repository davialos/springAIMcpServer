package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.model.ModelUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultModelRouterTest {

    private static ChatModel model() {
        return (ChatModel) Proxy.newProxyInstance(DefaultModelRouterTest.class.getClassLoader(),
                new Class<?>[] {ChatModel.class}, (proxy, method, args) -> null);
    }

    private final ChatModel openAi = model();
    private final ChatModel ollama = model();
    private final ChatModel azure = model();
    private final DefaultModelRouter router = new DefaultModelRouter(Map.of(
            "openAiChatModel", openAi, "ollamaChatModel", ollama, "azureOpenAiChatModel", azure));

    private static ModelSelection selection(String provider, ModelSelection fallback) {
        return new ModelSelection(provider, "some-model", null, null, fallback);
    }

    @Test
    void providerIdsMatchBeanNamesIgnoringCaseAndPunctuation() {
        assertThat(router.resolve(selection("openai", null), null)).isSameAs(openAi);
        assertThat(router.resolve(selection("OpenAI", null), null)).isSameAs(openAi);
        assertThat(router.resolve(selection("azure-openai", null), null)).isSameAs(azure);
        assertThat(router.resolve(selection("ollama", null), null)).isSameAs(ollama);
    }

    @Test
    void anUnknownProviderUsesTheFallbackSelection() {
        ModelSelection withFallback = selection("anthropic", selection("ollama", null));

        assertThat(router.resolve(withFallback, null)).isSameAs(ollama);
    }

    @Test
    void anUnknownProviderWithoutFallbackIsUnavailableNeverSilentlySubstituted() {
        assertThatThrownBy(() -> router.resolve(selection("anthropic", null), null))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("anthropic");
    }

    @Test
    void noChatModelsMeansEveryTurnIsUnavailable() {
        DefaultModelRouter empty = new DefaultModelRouter(Map.of());

        assertThatThrownBy(() -> empty.resolve(selection("openai", null), null))
                .isInstanceOf(ModelUnavailableException.class);
    }

    @Test
    void twoBeansOfTheSameProviderAreRejectedAtStartup() {
        assertThatThrownBy(() -> new DefaultModelRouter(Map.of("openAiChatModel", openAi, "openaiChatModel", model())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveModelReportsTheSelectionThatMatchedSoTheFallbackKeepsItsOwnModelName() {
        ModelSelection fallback = new ModelSelection("ollama", "llama3.1", 0.1, 512, null);
        ModelSelection primary = new ModelSelection("anthropic", "claude-x", 0.9, 4096, fallback);

        var resolved = router.resolveModel(primary, null);

        assertThat(resolved.model()).isSameAs(ollama);
        assertThat(resolved.selection()).isSameAs(fallback);
        assertThat(router.resolveModel(selection("openai", null), null).selection().providerId()).isEqualTo("openai");
    }
}
