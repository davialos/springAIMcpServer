package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.agent.ModelSelection;
import com.springaimcpservercommon.ai.model.ModelUnavailableException;
import com.springaimcpservercommon.autoconfigure.ResilientChatModel.Candidate;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientChatModelTest {

    private final ProviderBreakerTest.TestClock clock = new ProviderBreakerTest.TestClock();

    /** A scripted provider that records the prompts it received. */
    private static final class Fake implements ChatModel {
        final String name;
        final List<Prompt> prompts = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        Function<Prompt, ChatResponse> onCall;
        Function<Prompt, Flux<ChatResponse>> onStream;

        Fake(String name) {
            this.name = name;
            this.onCall = p -> answer(name);
            this.onStream = p -> Flux.just(answer(name));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            prompts.add(prompt);
            return onCall.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls.incrementAndGet();
            prompts.add(prompt);
            return onStream.apply(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model(name + "-default").build();
        }
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static String text(ChatResponse r) {
        return r.getResult().getOutput().getText();
    }

    private final Fake primary = new Fake("primary");
    private final Fake fallback = new Fake("fallback");

    private ResilientChatModel chain(int threshold) {
        return new ResilientChatModel(List.of(
                new Candidate(new ModelSelection("a", "primary-model", 0.9, null, null), primary,
                        new ProviderBreaker(threshold, Duration.ofSeconds(30), clock)),
                new Candidate(new ModelSelection("b", "fallback-model", 0.1, 256, null), fallback,
                        new ProviderBreaker(threshold, Duration.ofSeconds(30), clock))));
    }

    private static final ToolCallback TOOL = new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("find_orders").description("d").inputSchema("{}").build();
        }

        @Override
        public String call(String in) {
            return "{}";
        }
    };

    private static Prompt prompt() {
        return new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("hi")),
                ToolCallingChatOptions.builder().model("primary-model").toolCallbacks(List.of(TOOL)).build());
    }

    @Test
    void aHealthyPrimaryAnswersAndTheFallbackIsNeverTouched() {
        assertThat(text(chain(3).call(prompt()))).isEqualTo("primary");
        assertThat(fallback.calls).hasValue(0);
    }

    @Test
    void aFailingPrimaryFailsOverAndTheFallbackGetsItsOwnOptionsAndTheTools() {
        primary.onCall = p -> {
            throw new IllegalStateException("provider down");
        };

        assertThat(text(chain(3).call(prompt()))).isEqualTo("fallback");

        assertThat(fallback.prompts.get(0).getOptions()).isInstanceOfSatisfying(ToolCallingChatOptions.class, o -> {
            assertThat(o.getModel()).isEqualTo("fallback-model");
            assertThat(o.getTemperature()).isEqualTo(0.1);
            assertThat(o.getMaxTokens()).isEqualTo(256);
            assertThat(o.getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                    .containsExactly("find_orders");
        });
    }

    @Test
    void whenEveryProviderFailsTheLastErrorIsThrown() {
        primary.onCall = p -> {
            throw new IllegalStateException("one");
        };
        fallback.onCall = p -> {
            throw new IllegalArgumentException("two");
        };

        assertThatThrownBy(() -> chain(3).call(prompt())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOpenBreakerSkipsTheProviderWithoutCallingIt() {
        primary.onCall = p -> {
            throw new IllegalStateException("down");
        };
        ResilientChatModel model = chain(2);
        model.call(prompt());
        model.call(prompt()); // second failure opens primary's breaker
        int callsBefore = primary.calls.get();

        assertThat(text(model.call(prompt()))).isEqualTo("fallback");
        assertThat(primary.calls).hasValue(callsBefore);

        clock.advance(Duration.ofSeconds(31)); // half-open: one probe, which now succeeds
        primary.onCall = p -> answer("primary");
        assertThat(text(model.call(prompt()))).isEqualTo("primary");
    }

    @Test
    void whenAllBreakersAreOpenTheCallFailsFastAsUnavailable() {
        primary.onCall = p -> {
            throw new IllegalStateException("down");
        };
        fallback.onCall = p -> {
            throw new IllegalStateException("down");
        };
        ResilientChatModel model = chain(1);
        assertThatThrownBy(() -> model.call(prompt())).isInstanceOf(IllegalStateException.class);
        int calls = primary.calls.get() + fallback.calls.get();

        assertThatThrownBy(() -> model.call(prompt())).isInstanceOf(ModelUnavailableException.class);
        assertThat(primary.calls.get() + fallback.calls.get()).isEqualTo(calls);
    }

    @Test
    void aStreamThatFailsBeforeAnyOutputFailsOver() {
        primary.onStream = p -> Flux.error(new IllegalStateException("connect failed"));

        List<ChatResponse> out = chain(3).stream(prompt()).collectList().block();

        assertThat(out).extracting(ResilientChatModelTest::text).containsExactly("fallback");
    }

    @Test
    void aStreamThatFailsAfterOutputWasSentIsNotSplicedWithAnotherProvider() {
        primary.onStream = p -> Flux.concat(Flux.just(answer("par")), Flux.error(new IllegalStateException("cut")));

        assertThatThrownBy(() -> chain(3).stream(prompt()).collectList().block())
                .isInstanceOf(IllegalStateException.class);
        assertThat(fallback.calls).hasValue(0);
    }
}
