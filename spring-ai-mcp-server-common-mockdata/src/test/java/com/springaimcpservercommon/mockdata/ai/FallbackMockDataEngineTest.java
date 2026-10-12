package com.springaimcpservercommon.mockdata.ai;

import com.springaimcpservercommon.annotations.FakeAi;
import com.springaimcpservercommon.annotations.FakeData;
import com.springaimcpservercommon.mockdata.ai.FallbackMockDataEngine.Mode;
import com.springaimcpservercommon.mockdata.engine.DatafakerEngine;
import com.springaimcpservercommon.mockdata.engine.FieldGap;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackMockDataEngineTest {

    record Ticket(@FakeData("name.fullName") String customer, @FakeAi("a polite complaint, 2 sentences") String text, String subject) {
    }

    /** A "model" that answers with recognisable texts and counts its calls. */
    static final class FakeModel implements AiMockGenerator {
        final AtomicInteger calls = new AtomicInteger();
        final List<List<FieldGap>> hints = new ArrayList<>();
        boolean fail;

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> generate(Class<T> type, int count, List<FieldGap> gaps) throws AiMockException {
            calls.incrementAndGet();
            hints.add(gaps);
            if (fail) {
                throw new AiMockException("provider down");
            }
            List<Ticket> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.add(new Ticket("ignored", "AI text " + i, "AI subject " + i));
            }
            return (List<T>) out;
        }
    }

    private static AiMockPool pool(AiMockGenerator g, AiMockPool.Settings s) {
        return new AiMockPool(g, s, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void aiFillsOnlyTheGapsAndAThousandObjectsCostOneModelCall() {
        FakeModel model = new FakeModel();
        var engine = new FallbackMockDataEngine(new DatafakerEngine(DatafakerEngine.Options.defaults()),
                pool(model, new AiMockPool.Settings(5, 10, 3, Duration.ofMinutes(1))), Mode.AI_FALLBACK);
        List<Ticket> all = engine.generate(Ticket.class, 1000);
        assertThat(model.calls).hasValue(1);
        assertThat(all).allSatisfy(t -> {
            assertThat(t.text()).startsWith("AI text");           // the @FakeAi field comes from the model
            assertThat(t.customer()).contains(" ").isNotEqualTo("ignored"); // annotated with Datafaker: not from the model
            assertThat(t.subject()).doesNotStartWith("AI subject"); // no gap: Datafaker's heuristic stays
        });
        assertThat(all.stream().map(Ticket::text).distinct()).hasSize(5); // the pool is reused round-robin
        assertThat(model.hints.getFirst()).extracting(FieldGap::path).containsExactly("text");
    }

    @Test
    void fakerModeNeverAsksTheModel() {
        FakeModel model = new FakeModel();
        var engine = new FallbackMockDataEngine(new DatafakerEngine(DatafakerEngine.Options.defaults()), pool(model, AiMockPool.Settings.defaults()), Mode.FAKER);
        Ticket t = engine.generate(Ticket.class);
        assertThat(model.calls).hasValue(0);
        assertThat(t.text()).isNotNull();
    }

    @Test
    void withoutAModelPlaceholdersStayAndNothingFails() {
        var engine = new FallbackMockDataEngine(new DatafakerEngine(DatafakerEngine.Options.defaults()), null, Mode.AI_FALLBACK);
        Ticket t = engine.generate(Ticket.class);
        assertThat(t.text()).isNotNull();
        assertThat(t.customer()).isNotBlank();
    }

    @Test
    void aFailingModelOpensTheCircuitAndTheLoadTestKeepsRunning() {
        FakeModel model = new FakeModel();
        model.fail = true;
        var engine = new FallbackMockDataEngine(new DatafakerEngine(DatafakerEngine.Options.defaults()),
                pool(model, new AiMockPool.Settings(5, 100, 2, Duration.ofMinutes(5))), Mode.AI_FALLBACK);
        for (int i = 0; i < 50; i++) {
            assertThat(engine.generate(Ticket.class).customer()).isNotBlank();
        }
        assertThat(model.calls).as("two failures open the circuit; no more calls during the cool-down").hasValue(2);
    }

    @Test
    void theCallBudgetBoundsModelUsageButSamplesAreStillReused() {
        FakeModel model = new FakeModel();
        AiMockPool pool = pool(model, new AiMockPool.Settings(3, 1, 3, Duration.ofMinutes(1)));
        assertThat(pool.sample(Ticket.class, List.of())).isPresent();
        assertThat(pool.sample(Ticket.class, List.of())).isPresent();
        assertThat(pool.calls()).isEqualTo(1);
        assertThat(pool.sample(String.class, List.of())).as("no budget left for another type").isEmpty();
    }

    @Test
    void circuitClosesAfterTheCooldown() {
        FakeModel model = new FakeModel();
        model.fail = true;
        var time = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        AiMockPool pool = new AiMockPool(model, new AiMockPool.Settings(2, 100, 1, Duration.ofMinutes(1)), clock);
        assertThat(pool.sample(Ticket.class, List.of())).isEmpty();      // fails, circuit opens
        assertThat(pool.sample(Ticket.class, List.of())).isEmpty();      // skipped
        assertThat(model.calls).hasValue(1);
        time.set(Instant.parse("2026-01-01T00:02:00Z"));
        model.fail = false;
        assertThat(pool.sample(Ticket.class, List.of())).isPresent();    // retried after the cool-down
        assertThat(model.calls).hasValue(2);
    }

    @Test
    void aiModeSendsEveryUnannotatedFieldToTheModel() {
        FakeModel model = new FakeModel();
        DatafakerEngine datafaker = new DatafakerEngine(new DatafakerEngine.Options(java.util.Locale.ENGLISH, null, 4, 3, true, DatafakerEngine.Options.defaults().providers()));
        var engine = new FallbackMockDataEngine(datafaker, pool(model, AiMockPool.Settings.defaults()), Mode.AI);
        Ticket t = engine.generate(Ticket.class);
        assertThat(t.subject()).startsWith("AI subject");
        assertThat(t.text()).startsWith("AI text");
        assertThat(t.customer()).as("a Datafaker annotation still wins").doesNotStartWith("ignored");
    }

    @Test
    void parsesModelAnswersWrappedInProseAndFences() throws Exception {
        List<Ticket> parsed = AiResponseParser.parse("Sure! ```json\n[{\"customer\":\"A\",\"text\":\"t\",\"subject\":\"s\",\"extra\":1}]\n```", Ticket.class);
        assertThat(parsed).containsExactly(new Ticket("A", "t", "s"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AiResponseParser.parse("no json here", Ticket.class)).isInstanceOf(AiMockException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AiResponseParser.parse("[{\"customer\": [1,2]}]", Ticket.class))
                .isInstanceOf(AiMockException.class).hasMessageNotContaining("[1,2]");
    }

    @Test
    void describesAClassForThePrompt() {
        String d = ClassDescriber.describe(Ticket.class);
        assertThat(d).contains("- customer: String — looks like Datafaker name.fullName")
                .contains("- text: String — a polite complaint, 2 sentences").contains("- subject: String");
    }
}
