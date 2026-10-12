package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.annotations.CustomFakeData;
import com.springaimcpservercommon.annotations.CustomFakerProvider;
import com.springaimcpservercommon.annotations.FakeAi;
import com.springaimcpservercommon.annotations.FakeData;
import com.springaimcpservercommon.core.environment.DefaultEnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSafetyPolicy;
import com.springaimcpservercommon.core.environment.EnvironmentSignals;
import com.springaimcpservercommon.mockdata.MockDataEngine;
import com.springaimcpservercommon.mockdata.ai.AiMockException;
import com.springaimcpservercommon.mockdata.ai.AiMockGenerator;
import com.springaimcpservercommon.mockdata.engine.FieldGap;
import com.springaimcpservercommon.mockdata.simulation.LoadSimulator;
import com.springaimcpservercommon.mockdata.simulation.SimulationRefusedException;
import com.springaimcpservercommon.mockdata.simulation.SimulationSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaiMockDataWiringTest {

    public record Ticket(@FakeData("name.fullName") String customer, @FakeAi("a polite complaint about a late delivery") String text,
                         @CustomFakeData(provider = Fixed.class) String token) {
    }

    public static class Fixed implements CustomFakerProvider {
        @Override
        public Object provide(RandomGenerator random) {
            return "from-reflection";
        }
    }

    /** A "model" that records the prompts it receives. */
    static final class StubModel implements ChatModel {
        final List<String> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "Here you go:\n[{\"customer\":\"x\",\"text\":\"Where is my parcel? It is two weeks late.\",\"token\":\"t\"},"
                            + "{\"customer\":\"y\",\"text\":\"The box arrived damaged and late.\",\"token\":\"t\"}]"))));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WithModel {
        @Bean
        StubModel chatModel() {
            return new StubModel();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DaiMockDataAutoConfiguration.class));

    @Test
    void worksWithoutAnyModelAndLeavesTheSimulatorOff() {
        runner.run(ctx -> {
            MockDataEngine engine = ctx.getBean(MockDataEngine.class);
            Ticket t = engine.generate(Ticket.class);
            assertThat(t.customer()).contains(" ");
            assertThat(t.token()).isEqualTo("from-reflection");
            assertThat(t.text()).isNotNull();
            assertThat(ctx).doesNotHaveBean(LoadSimulator.class);
        });
    }

    @Test
    void aSpringAiModelFillsTheAiFieldsFromOneBatchedCall() {
        runner.withUserConfiguration(WithModel.class).run(ctx -> {
            MockDataEngine engine = ctx.getBean(MockDataEngine.class);
            List<Ticket> all = engine.generate(Ticket.class, 200);
            assertThat(all).allSatisfy(t -> {
                assertThat(t.text()).containsAnyOf("parcel", "damaged");
                assertThat(t.customer()).isNotIn("x", "y");          // Datafaker annotation wins
                assertThat(t.token()).isEqualTo("from-reflection");  // custom provider wins
            });
            StubModel model = ctx.getBean(StubModel.class);
            assertThat(model.prompts).hasSize(1);
            assertThat(model.prompts.getFirst()).contains("Ticket", "a polite complaint about a late delivery", "- customer: String");
        });
    }

    @Test
    void fakerModeNeverCallsTheModel() {
        runner.withUserConfiguration(WithModel.class).withPropertyValues("dynamic.ai.agent.mockdata.mode=FAKER").run(ctx -> {
            ctx.getBean(MockDataEngine.class).generate(Ticket.class, 20);
            assertThat(ctx.getBean(StubModel.class).prompts).isEmpty();
        });
    }

    @Test
    void aHostSuppliedAiPortReplacesTheSpringAiAdapter() {
        runner.withBean(AiMockGenerator.class, () -> new AiMockGenerator() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> generate(Class<T> type, int count, List<FieldGap> hints) throws AiMockException {
                return (List<T>) List.of(new Ticket("c", "from the host's own port", "t"));
            }
        }).withUserConfiguration(WithModel.class).run(ctx -> {
            assertThat(ctx.getBean(MockDataEngine.class).generate(Ticket.class).text()).isEqualTo("from the host's own port");
            assertThat(ctx.getBean(StubModel.class).prompts).isEmpty();
        });
    }

    @Test
    void customProviderBeansAreUsedBeforeReflection() {
        record Tok(@CustomFakeData(provider = Beanish.class) String token) {
        }
        runner.withBean(Beanish.class, () -> new Beanish("from-a-bean")).run(ctx ->
                assertThat(ctx.getBean(MockDataEngine.class).generate(Tok.class).token()).isEqualTo("from-a-bean"));
    }

    public static class Beanish implements CustomFakerProvider {
        private final String value;

        public Beanish(String value) {
            this.value = value;
        }

        @Override
        public Object provide(RandomGenerator random) {
            return value;
        }
    }

    @Test
    void theSimulatorRefusesWithoutAnEnvironmentAndInProductionButRunsInDev() {
        String[] on = {"dynamic.ai.agent.mockdata.simulator.enabled=true"};
        SimulationSpec spec = SimulationSpec.post(URI.create("http://localhost:1/x"), null, 1, Duration.ofSeconds(1));
        // no environment beans: fail closed
        runner.withPropertyValues(on).run(ctx -> assertThatThrownBy(() -> ctx.getBean(LoadSimulator.class).start(spec))
                .isInstanceOf(SimulationRefusedException.class).hasMessageContaining("production"));
        // PROD
        runner.withPropertyValues(on).withBean(EnvironmentSafetyPolicy.class, DefaultEnvironmentSafetyPolicy::defaults)
                .withBean(EnvironmentSignals.class, () -> new EnvironmentSignals("PROD", List.of(), null, "app"))
                .run(ctx -> assertThatThrownBy(() -> ctx.getBean(LoadSimulator.class).start(spec)).isInstanceOf(SimulationRefusedException.class));
        // DEV: allowed (the target refuses connections, which the report counts)
        runner.withPropertyValues(on).withBean(EnvironmentSafetyPolicy.class, DefaultEnvironmentSafetyPolicy::defaults)
                .withBean(EnvironmentSignals.class, () -> new EnvironmentSignals("DEV", List.of(), null, "app"))
                .run(ctx -> {
                    var report = ctx.getBean(LoadSimulator.class).run(spec);
                    assertThat(report.requests()).isPositive();
                    assertThat(report.failures()).isEqualTo(report.requests());
                });
    }

    @Test
    void simulatorLimitsComeFromProperties() {
        runner.withPropertyValues("dynamic.ai.agent.mockdata.simulator.enabled=true", "dynamic.ai.agent.mockdata.simulator.max-virtual-users=5",
                        "dynamic.ai.agent.mockdata.simulator.allowed-hosts=*.stage.example.com")
                .withBean(EnvironmentSafetyPolicy.class, DefaultEnvironmentSafetyPolicy::defaults)
                .withBean(EnvironmentSignals.class, () -> new EnvironmentSignals("TEST", List.of(), null, "app"))
                .run(ctx -> {
                    LoadSimulator sim = ctx.getBean(LoadSimulator.class);
                    assertThatThrownBy(() -> sim.start(SimulationSpec.post(URI.create("http://localhost:1/x"), null, 6, Duration.ofSeconds(1))))
                            .hasMessageContaining("virtualUsers 6 exceeds the limit 5");
                    assertThatThrownBy(() -> sim.start(SimulationSpec.post(URI.create("http://prod.example.com/x"), null, 1, Duration.ofSeconds(1))))
                            .hasMessageContaining("allow-list");
                });
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("dynamic.ai.agent.mockdata.enabled=false").run(ctx -> assertThat(ctx).doesNotHaveBean(MockDataEngine.class));
    }
}
