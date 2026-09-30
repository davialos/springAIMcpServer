package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.model.ModelRouter;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.security.permission.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelProviderAdminControllerTest {

    private final ProviderBreakerTest.TestClock clock = new ProviderBreakerTest.TestClock();
    private final ChatModel broken = new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new IllegalStateException("down");
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    };
    private final DefaultModelRouter router = new DefaultModelRouter(
            Map.of("openAiChatModel", broken, "ollamaChatModel", broken), 2, Duration.ofSeconds(30), clock);
    private final AdminAudit audit = mock(AdminAudit.class);
    private final AdminApi api = mock(AdminApi.class);
    private final DaiPrincipal caller = mock(DaiPrincipal.class);
    private final HttpServletRequest request = new MockHttpServletRequest();

    @SuppressWarnings("unchecked")
    private ModelProviderAdminController controller(ModelRouter modelRouter) {
        ObjectProvider<ModelRouter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(modelRouter);
        when(api.gate(any(), eq(Permission.OPS_KILLSWITCH), isNull())).thenReturn(new AdminApi.Gate(caller, null));
        return new ModelProviderAdminController(provider, audit, api, "node-a");
    }

    private void trip() {
        ModelSelection openai = new ModelSelection("openai", "gpt-x", null, null, null);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> router.resolveModel(openai, null).model().call(new Prompt("hi")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void listsEveryProviderWithThisNodesBreakerState() {
        trip();

        ResponseEntity<?> response = controller(router).providers(request);

        assertThat(response.getBody()).isInstanceOfSatisfying(ModelProviderAdminController.ProvidersView.class, v -> {
            assertThat(v.node()).isEqualTo("node-a");
            assertThat(v.available()).isTrue();
            assertThat(v.providers()).extracting(ModelProviderAdminController.Provider::provider)
                    .containsExactly("ollama", "openai");
            assertThat(v.providers().get(0).state()).isEqualTo("CLOSED");
            assertThat(v.providers().get(1).state()).isEqualTo("OPEN");
            assertThat(v.providers().get(1).consecutiveFailures()).isEqualTo(2);
            assertThat(v.providers().get(1).retryAt()).isEqualTo(v.providers().get(1).openedAt().plusSeconds(30));
        });
        clock.advance(Duration.ofSeconds(31));
        var later = (ModelProviderAdminController.ProvidersView) controller(router).providers(request).getBody();
        assertThat(later.providers().get(1).state()).isEqualTo("HALF_OPEN");
    }

    @Test
    void resetClosesTheBreakerAndIsAudited() {
        trip();

        assertThat(controller(router).resetBreaker("OpenAI", request).getStatusCode().value()).isEqualTo(204);

        var view = (ModelProviderAdminController.ProvidersView) controller(router).providers(request).getBody();
        assertThat(view.providers().get(1).state()).isEqualTo("CLOSED");
        verify(audit).record(eq(caller), eq(AuditCategory.ADMIN), eq(AuditPlane.CONTROL), eq("MODEL_BREAKER_RESET"),
                isNull(), eq("model_provider"), eq("openai"), isNull(), isNull(), eq(Map.of("node", "node-a")));
    }

    @Test
    void resettingAnUnknownProviderIsNotFoundAndNotAudited() {
        assertThat(controller(router).resetBreaker("anthropic", request).getStatusCode().value()).isEqualTo(404);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aCustomRouterReportsNoBreakers() {
        var view = (ModelProviderAdminController.ProvidersView) controller(mock(ModelRouter.class))
                .providers(request).getBody();
        assertThat(view.available()).isFalse();
        assertThat(view.providers()).isEmpty();
    }
}
