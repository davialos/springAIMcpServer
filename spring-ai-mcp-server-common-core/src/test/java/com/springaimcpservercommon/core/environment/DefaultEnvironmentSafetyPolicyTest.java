package com.springaimcpservercommon.core.environment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultEnvironmentSafetyPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final DefaultEnvironmentSafetyPolicy policy = DefaultEnvironmentSafetyPolicy.defaults();

    private static EnvironmentSignals signals(String tier, String... profiles) {
        return new EnvironmentSignals(tier, Arrays.asList(profiles), null, "orders");
    }

    @ParameterizedTest(name = "tier={0} profiles={1} -> {2} conflict={3}")
    @CsvSource(delimiter = ';', nullValues = "NULL", value = {
            "dev;;DEV;false",
            "Test;;TEST;false",
            "STAGE;;STAGE;false",
            "prod;;PROD;false",
            "NULL;;UNKNOWN;false",
            "'';;UNKNOWN;false",
            "banana;;UNKNOWN;false",
            "NULL;prod;PROD;false",
            "NULL;Production;PROD;false",
            "NULL;eu-PROD;PROD;false",
            "NULL;local,live;PROD;false",
            "NULL;prd;PROD;false",
            "NULL;dev,local;UNKNOWN;false",
            "NULL;products;UNKNOWN;false",
            "dev;prod;PROD;true",
            "stage;orders-prod;PROD;true",
            "test;LIVE;PROD;true",
            "prod;prod;PROD;false",
            "dev;local;DEV;false",
            "banana;prod;PROD;false"
    })
    void resolvesTierMatrix(String tier, String profiles, EnvironmentTier expected, boolean conflict) {
        String[] active = profiles == null ? new String[0] : profiles.split(",");
        EnvironmentIdentity id = policy.identify(signals(tier, active));
        assertThat(id.tier()).isEqualTo(expected);
        assertThat(id.conflict()).isEqualTo(conflict);
        assertThat(id.productionRules()).isEqualTo(expected == EnvironmentTier.PROD || expected == EnvironmentTier.UNKNOWN);
        assertThat(id.explanation()).isNotBlank();
    }

    @Test
    void recordsSourceAndEnvironmentId() {
        EnvironmentIdentity explicit = policy.identify(signals("dev"));
        assertThat(explicit.source()).isEqualTo(EnvironmentIdentity.Source.EXPLICIT);
        assertThat(explicit.environmentId()).isEqualTo("orders-dev");
        EnvironmentIdentity heuristic = policy.identify(signals(null, "prod"));
        assertThat(heuristic.source()).isEqualTo(EnvironmentIdentity.Source.PROFILE_HEURISTIC);
        assertThat(heuristic.matchedProdProfiles()).containsExactly("prod");
        EnvironmentIdentity unset = policy.identify(new EnvironmentSignals(null, List.of(), "orders-eu-1", null));
        assertThat(unset.source()).isEqualTo(EnvironmentIdentity.Source.DEFAULT);
        assertThat(unset.environmentId()).isEqualTo("orders-eu-1");
    }

    @ParameterizedTest
    @EnumSource(Capability.class)
    void capabilityMatrixWithoutOverride(Capability capability) {
        for (EnvironmentTier tier : List.of(EnvironmentTier.DEV, EnvironmentTier.TEST, EnvironmentTier.STAGE)) {
            assertThat(policy.isEnabled(capability, policy.identify(signals(tier.name())))).isTrue();
        }
        boolean alwaysOn = Set.of(Capability.DATA_PLANE, Capability.REVIEWED_WRITES, Capability.MCP_SERVER,
                Capability.OPS_VIEWS).contains(capability);
        assertThat(policy.isEnabled(capability, policy.identify(signals("prod")))).isEqualTo(alwaysOn);
        assertThat(policy.isEnabled(capability, policy.identify(signals(null)))).isEqualTo(alwaysOn);
        assertThat(policy.isEnabled(capability, policy.identify(signals("dev", "prod")))).isEqualTo(alwaysOn);
    }

    @Test
    void overrideEnablesListedCapabilitiesUntilExpiry() {
        MutableClock clock = new MutableClock(NOW);
        ProductionOverride override = ProductionOverride.validated(Set.of(Capability.AUTHORING, Capability.PLAYGROUND),
                NOW.plus(Duration.ofHours(2)), "INC-4412", clock);
        DefaultEnvironmentSafetyPolicy withOverride = new DefaultEnvironmentSafetyPolicy(
                DefaultEnvironmentSafetyPolicy.DEFAULT_PROD_PROFILE_PATTERNS, override, clock);
        EnvironmentIdentity prod = withOverride.identify(signals("prod"));

        assertThat(withOverride.isEnabled(Capability.AUTHORING, prod)).isTrue();
        assertThat(withOverride.isEnabled(Capability.PLAYGROUND, prod)).isTrue();
        assertThat(withOverride.isEnabled(Capability.INTROSPECTION, prod)).isFalse();
        assertThat(withOverride.isEnabled(Capability.QUERY_PREVIEW, prod)).isFalse();

        clock.set(NOW.plus(Duration.ofHours(2)));
        assertThat(withOverride.isEnabled(Capability.AUTHORING, prod)).isFalse();
        assertThat(withOverride.isEnabled(Capability.DATA_PLANE, prod)).isTrue();
    }

    @Test
    void overrideValidation() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> ProductionOverride.validated(Set.of(Capability.AUTHORING),
                NOW.plus(Duration.ofHours(73)), "too long", clock)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProductionOverride.validated(Set.of(Capability.AUTHORING), NOW.plus(Duration.ofHours(72)), "ok", clock)
                .activeAt(NOW)).isTrue();
        assertThatThrownBy(() -> ProductionOverride.validated(Set.of(Capability.QUERY_PREVIEW),
                NOW.plusSeconds(60), "never", clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductionOverride.validated(Set.of(Capability.AUTHORING), NOW.plusSeconds(60), " ", clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductionOverride.validated(Set.of(), NOW.plusSeconds(60), "empty", clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customPatternsAreCaseInsensitiveGlobs() {
        DefaultEnvironmentSafetyPolicy custom = new DefaultEnvironmentSafetyPolicy(List.of("p?d", "*-live-*"), null,
                Clock.systemUTC());
        assertThat(custom.matchesProdPattern("PRD")).isTrue();
        assertThat(custom.matchesProdPattern("eu-LIVE-1")).isTrue();
        assertThat(custom.matchesProdPattern("prod")).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
