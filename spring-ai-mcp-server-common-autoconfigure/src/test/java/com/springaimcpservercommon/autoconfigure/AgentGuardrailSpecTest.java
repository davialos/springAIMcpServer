package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.core.display.DisplayNode;
import com.springaimcpservercommon.core.display.DisplayTemplateException;
import com.springaimcpservercommon.core.guard.InputValidationPolicy;
import com.springaimcpservercommon.persistence.config.PublishedResource;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.ResourceStatus;
import com.springaimcpservercommon.persistence.config.RevisionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The agent spec carries prompt validation and the display template (F-76, LLD-06 §8). */
class AgentGuardrailSpecTest {

    private static PublishedResource resource(String spec) {
        return new PublishedResource(UUID.randomUUID(), UUID.randomUUID(), ResourceKind.AGENT, "shop-bot",
                ResourceStatus.ACTIVE, null, UUID.randomUUID(), 1, RevisionState.PUBLISHED, spec, 1, "sha256:x", null);
    }

    @Test
    void readsInputValidationAndTheDisplayTemplate() {
        AgentDefinition agent = DaiPersistenceAutoConfiguration.parseAgent(resource("""
                {"systemPrompt": "Help with orders.",
                 "guardrails": {"piiRedactionOutput": true,
                   "inputValidation": {"threatDetection": true, "businessScope": true, "minRelevance": 0.4,
                                       "scopeKeywords": ["returns"]}},
                 "output": {"mode": "text", "display": {"version": 1, "blocks": [
                   {"type": "text"},
                   {"type": "table", "source": "orders", "columns": [{"path": "id", "label": "Order #"}]}]}}}
                """));

        assertThat(agent.guardrails().inputValidation()).isEqualTo(
                new InputValidationPolicy(true, true, 0.4, InputValidationPolicy.DEFAULT_MIN_TERMS, List.of("returns")));
        assertThat(agent.output().display()).isNotNull();
        assertThat(agent.output().display().blocks()).hasSize(2)
                .element(1).isInstanceOf(DisplayNode.Table.class);
    }

    @Test
    void omittedSectionsMeanNoAgentLevelValidationAndTheAutomaticLayout() {
        AgentDefinition agent = DaiPersistenceAutoConfiguration.parseAgent(resource("{\"guardrails\": {}}"));

        assertThat(agent.guardrails().inputValidation()).isEqualTo(InputValidationPolicy.OFF);
        assertThat(agent.output().display()).isNull();
    }

    @Test
    void anInvalidDisplayTemplateFailsTheAgentLoad() {
        assertThatThrownBy(() -> DaiPersistenceAutoConfiguration.parseAgent(resource("""
                {"output": {"display": {"version": 1, "blocks": [{"type": "chart"}]}}}
                """))).isInstanceOf(DisplayTemplateException.class);
    }
}
