package com.springaimcpservercommon.ai.advisor;

import com.springaimcpservercommon.ai.agent.AgentDefinition;
import com.springaimcpservercommon.ai.agent.GuardrailSpec;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvocationGuardAdvisorTest {

    private final AgentDefinition agent = mock(AgentDefinition.class);
    private final DaiPrincipal principal = mock(DaiPrincipal.class);

    private InvocationGuardAdvisor guard(boolean enabled, boolean withinBudget) {
        return guard(enabled, withinBudget, GuardrailSpec.OFF);
    }

    private InvocationGuardAdvisor guard(boolean enabled, boolean withinBudget, GuardrailSpec spec) {
        when(agent.guardrails()).thenReturn(spec);
        return new InvocationGuardAdvisor(agent, principal, id -> enabled, (a, p) -> withinBudget);
    }

    private static ChatClientRequest request(String text) {
        return new ChatClientRequest(new Prompt(text), Map.of());
    }

    @Test
    void streamedTurnOfAKillSwitchedAgentNeverReachesTheModel() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        var responses = guard(false, true).adviseStream(request("hi"), chain).collectList().block();

        assertThat(responses).singleElement().extracting(r -> r.chatResponse().getResult().getOutput().getText())
                .asString().contains("agent_disabled");
        verify(chain, never()).nextStream(any());
    }

    @Test
    void streamedTurnWithinBudgetIsForwarded() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatClientResponse passed = mock(ChatClientResponse.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(passed));

        var responses = guard(true, true).adviseStream(request("hi"), chain).collectList().block();

        assertThat(responses).containsExactly(passed);
    }

    @Test
    void budgetIsCheckedAfterTheInputChecks() {
        assertThat(guard(true, false).checkInput("hi")).isEmpty();
        assertThat(guard(true, false).check("hi")).hasValueSatisfying(v ->
                assertThat(v.code()).isEqualTo("budget_exhausted"));
    }

    @Test
    void inputGuardrailsApplyToTheStreamPath() {
        GuardrailSpec spec = new GuardrailSpec(5, List.of("secret"), false, false, List.of(), 0);
        assertThat(guard(true, true, spec).checkInput("way too long message")).hasValueSatisfying(v ->
                assertThat(v.code()).isEqualTo("input_too_large"));
        GuardrailSpec blocked = new GuardrailSpec(0, List.of("secret"), false, false, List.of(), 0);
        assertThat(guard(true, true, blocked).checkInput("tell me the SECRET")).hasValueSatisfying(v ->
                assertThat(v.code()).isEqualTo("input_blocked"));
    }
}
