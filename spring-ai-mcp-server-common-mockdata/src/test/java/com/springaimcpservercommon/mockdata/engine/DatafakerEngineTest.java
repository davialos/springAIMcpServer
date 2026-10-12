package com.springaimcpservercommon.mockdata.engine;

import com.springaimcpservercommon.annotations.CustomFakeData;
import com.springaimcpservercommon.annotations.CustomFakerProvider;
import com.springaimcpservercommon.annotations.FakeAi;
import com.springaimcpservercommon.annotations.FakeData;
import com.springaimcpservercommon.annotations.FakeIgnore;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatafakerEngineTest {

    public static class TokenProvider implements CustomFakerProvider {
        @Override
        public Object provide(RandomGenerator random) {
            return "tok-" + Long.toHexString(random.nextLong());
        }
    }

    enum Tier { FREE, PRO }

    public static class Node {
        String label;
        Node next;
    }

    record Address(@FakeData("address.city") String city, String zip, String country) {
    }

    record Customer(
            @FakeData("name.fullName") String fullName,
            @FakeData("internet.emailAddress") String email,
            @FakeData("number.numberBetween '18','65'") int age,
            @CustomFakeData(provider = TokenProvider.class) String token,
            @FakeIgnore String internal,
            String firstName, String phone, UUID id, Tier tier, boolean active, LocalDate born, Instant created,
            List<String> tags, Optional<String> nickname, Address address, List<Address> previous, Map<String, Integer> scores) {
    }

    public static class LegacyDto {
        @FakeData("name.firstName")
        private String first;
        private int quantity;
        private double price;
        private String city;
        private static final String CONSTANT = "x";
        private final String fixed = "keep";
    }

    @Test
    void fillsRecordsFromAnnotationsAndFromNamesAndTypes() {
        DatafakerEngine engine = new DatafakerEngine(DatafakerEngine.Options.defaults());
        Customer c = engine.generate(Customer.class);
        assertThat(c.fullName()).contains(" ");
        assertThat(c.email()).matches(".+@.+\\..+");
        assertThat(c.age()).isBetween(18, 65);
        assertThat(c.token()).startsWith("tok-");
        assertThat(c.internal()).isNull();
        assertThat(c.firstName()).isNotBlank().doesNotContain("@");     // heuristic by name
        assertThat(c.phone()).isNotBlank();
        assertThat(c.id()).isNotNull();
        assertThat(c.tier()).isIn((Object[]) Tier.values());
        assertThat(c.born()).isNotNull();
        assertThat(c.created()).isNotNull();
        assertThat(c.tags()).isNotEmpty().allMatch(s -> !s.isBlank());
        assertThat(c.nickname()).isPresent();
        assertThat(c.address().city()).isNotBlank();
        assertThat(c.address().zip()).isNotBlank();
        assertThat(c.address().country()).isNotBlank();
        assertThat(c.previous()).isNotEmpty();
        assertThat(c.scores()).isNotEmpty().allSatisfy((k, v) -> assertThat(v).isPositive());
    }

    @Test
    void fillsPlainClassesAndSkipsStaticAndFinalFields() throws Exception {
        LegacyDto d = new DatafakerEngine(DatafakerEngine.Options.defaults()).generate(LegacyDto.class);
        assertThat(d.first).isNotBlank();
        assertThat(d.quantity).isBetween(1, 20);
        assertThat(d.price).isPositive();
        assertThat(d.city).isNotBlank();
        assertThat(d.fixed).isEqualTo("keep");
    }

    @Test
    void localeSwitchesTheLanguageOfGeneratedText() {
        record Greeting(@FakeData("address.city") String city, @FakeData(value = "address.city", locale = "ja") String stadt) {
        }
        DatafakerEngine ja = new DatafakerEngine(new DatafakerEngine.Options(Locale.GERMAN, 1L, 4, 3, false, DatafakerEngine.Options.defaults().providers()));
        Greeting g = IntStream.range(0, 20).mapToObj(i -> ja.generate(Greeting.class)).filter(x -> x.stadt().codePoints().anyMatch(cp -> cp > 0x2E80)).findFirst().orElse(null);
        assertThat(g).as("the per-field locale 'ja' yields Japanese characters").isNotNull();
    }

    @Test
    void sameSeedGivesTheSameTextValues() {
        record Person(@FakeData("name.fullName") String name, @FakeData("internet.emailAddress") String email, String city) {
        }
        DatafakerEngine a = new DatafakerEngine(DatafakerEngine.Options.defaults().withSeed(42L));
        DatafakerEngine b = new DatafakerEngine(DatafakerEngine.Options.defaults().withSeed(42L));
        for (int i = 0; i < 5; i++) {
            assertThat(a.generate(Person.class)).isEqualTo(b.generate(Person.class));
        }
    }

    @Test
    void reportsGapsWithPlaceholdersInsteadOfFailing() {
        interface Plugin {
        }
        record Ticket(@FakeAi("a polite complaint about a late delivery") String text,
                      @FakeData("nope.nothing") String broken,
                      @FakeData("name.fullName") int notANumber,
                      Plugin plugin,
                      String subject) {
        }
        Generated<Ticket> g = new DatafakerEngine(DatafakerEngine.Options.defaults()).generate(Ticket.class, Map.of());
        assertThat(g.value().text()).isNotNull();           // a placeholder
        assertThat(g.value().broken()).isNotNull();         // falls back to a heuristic value
        assertThat(g.value().plugin()).isNull();
        assertThat(g.value().subject()).isNotBlank();
        assertThat(g.gaps()).extracting(FieldGap::path, FieldGap::reason).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("text", FieldGap.Reason.AI_REQUESTED),
                org.assertj.core.groups.Tuple.tuple("broken", FieldGap.Reason.EXPRESSION_FAILED),
                org.assertj.core.groups.Tuple.tuple("notANumber", FieldGap.Reason.EXPRESSION_FAILED),
                org.assertj.core.groups.Tuple.tuple("plugin", FieldGap.Reason.UNSUPPORTED_TYPE));
        assertThat(g.gaps().stream().filter(x -> x.path().equals("text")).findFirst().orElseThrow().hint()).contains("late delivery");
        assertThat(g.complete()).isFalse();
    }

    @Test
    void overridesReplaceGeneratedValuesByPath() {
        Customer c = new DatafakerEngine(DatafakerEngine.Options.defaults()).generate(Customer.class,
                Map.of("firstName", "Zed", "address.city", "Atlantis")).value();
        assertThat(c.firstName()).isEqualTo("Zed");
        assertThat(c.address().city()).isEqualTo("Atlantis");
    }

    @Test
    void cutsRecursiveTypesAtTheDepthLimit() {
        Generated<Node> g = new DatafakerEngine(DatafakerEngine.Options.defaults()).generate(Node.class, Map.of());
        assertThat(g.value().label).isNotBlank();
        assertThat(g.gaps()).anyMatch(x -> x.reason() == FieldGap.Reason.DEPTH_LIMIT);
    }

    @Test
    void isSafeForManyVirtualThreads() throws Exception {
        DatafakerEngine engine = new DatafakerEngine(DatafakerEngine.Options.defaults());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Customer>> all = IntStream.range(0, 2000).mapToObj(i -> executor.submit(() -> engine.generate(Customer.class))).toList();
            Set<String> emails = new java.util.HashSet<>();
            for (var f : all) {
                emails.add(f.get().email());
            }
            assertThat(emails.size()).isGreaterThan(1500);
        }
    }

    @Test
    void refusesTypesItCannotInstantiate() {
        interface Contract {
        }
        assertThatThrownBy(() -> new DatafakerEngine(DatafakerEngine.Options.defaults()).generate(Contract.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be instantiated");
    }
}
