package com.springaimcpservercommon.security.authz.condition;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.security.TestFixtures;
import com.springaimcpservercommon.security.authz.ResourceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionParserTest {

    private final ConditionParser parser = new ConditionParser();
    private final DaiPrincipal principal = TestFixtures.user(UUID.randomUUID(), Set.of(), Set.of(), Map.of(),
            Map.of("region", "EU", "level", 5L, "teams", List.of("a", "b")), Classification.CONFIDENTIAL, Set.of());

    private ConditionContext at(String instant) {
        ResourceRef resource = new ResourceRef(UUID.randomUUID(), UUID.randomUUID(), "QUERY", "orders", Classification.INTERNAL);
        return new ConditionContext(principal, Map.of("tier", "PROD"), Map.of(), resource, Instant.parse(instant));
    }

    private boolean eval(String json, String instant) {
        return parser.parse(json).test(at(instant));
    }

    @Test
    void documentedExampleParsesAndEvaluates() {
        String json = "{\"principal.region\":{\"in\":[\"EU\"]},\"environment.tier\":{\"eq\":\"PROD\"},"
                + "\"time\":{\"between\":[\"08:00\",\"18:00\"],\"zone\":\"Europe/Berlin\"}}";
        GrantConditions conditions = parser.parse(json);
        assertThat(conditions.conditions()).hasSize(3);
        assertThat(conditions.test(at("2026-09-28T10:00:00Z"))).isTrue();   // 12:00 Berlin
        assertThat(conditions.test(at("2026-09-28T17:30:00Z"))).isFalse();  // 19:30 Berlin
    }

    @Test
    void operatorsHaveDocumentedSemantics() {
        String t = "2026-09-28T10:00:00Z";
        assertThat(eval("{\"principal.level\":{\"gte\":3,\"lt\":6}}", t)).isTrue();
        assertThat(eval("{\"principal.level\":{\"eq\":5.0}}", t)).isTrue();
        assertThat(eval("{\"principal.level\":{\"eq\":\"5\"}}", t)).isFalse();
        assertThat(eval("{\"principal.region\":{\"ne\":\"US\"}}", t)).isTrue();
        assertThat(eval("{\"principal.missing\":{\"ne\":\"US\"}}", t)).isFalse();
        assertThat(eval("{\"principal.missing\":{\"exists\":false}}", t)).isTrue();
        assertThat(eval("{\"principal.teams\":{\"contains\":\"a\"}}", t)).isTrue();
        assertThat(eval("{\"principal.teams\":{\"in\":[\"a\"]}}", t)).isFalse();
        assertThat(eval("{\"principal.teams\":{\"in\":[\"a\",\"b\",\"c\"]}}", t)).isTrue();
        assertThat(eval("{\"principal.teams\":{\"notIn\":[\"c\"]}}", t)).isTrue();
        assertThat(eval("{\"principal.clearance\":{\"eq\":\"CONFIDENTIAL\"}}", t)).isTrue();
        assertThat(eval("{\"resource.slug\":{\"startsWith\":\"ord\"}}", t)).isTrue();
        assertThat(eval("{\"principal.region\":{\"eq\":\"eu\"}}", t)).isFalse();
    }

    @Test
    void timeWindowsWrapMidnightAndRestrictDays() {
        String night = "{\"time\":{\"between\":[\"22:00\",\"06:00\"],\"zone\":\"UTC\",\"days\":[\"MON\"]}}";
        assertThat(eval(night, "2026-09-28T23:00:00Z")).isTrue();   // Monday 23:00
        assertThat(eval(night, "2026-09-29T05:00:00Z")).isTrue();   // Tuesday 05:00 belongs to Monday's window
        assertThat(eval(night, "2026-09-29T23:00:00Z")).isFalse();  // Tuesday night
        assertThat(eval(night, "2026-09-28T12:00:00Z")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"principal.region\":{\"regex\":\".*\"}}",
            "{\"principal.region\":{\"in\":[]}}",
            "{\"principal.region\":{\"in\":\"EU\"}}",
            "{\"principal.region\":{\"in\":[{\"x\":1}]}}",
            "{\"principal.region\":\"EU\"}",
            "{\"principal.region\":{}}",
            "{\"token.sub\":{\"eq\":\"x\"}}",
            "{\"principal\":{\"eq\":\"x\"}}",
            "{\"principal.level\":{\"gt\":\"3\"}}",
            "{\"principal.region\":{\"exists\":\"yes\"}}",
            "{\"time\":{\"between\":[\"08:00\",\"18:00\"]}}",
            "{\"time\":{\"between\":[\"8\",\"18:00\"],\"zone\":\"UTC\"}}",
            "{\"time\":{\"between\":[\"08:00\",\"08:00\"],\"zone\":\"UTC\"}}",
            "{\"time\":{\"between\":[\"08:00\",\"18:00\"],\"zone\":\"Mars/Base\"}}",
            "{\"time\":{\"between\":[\"08:00\",\"18:00\"],\"zone\":\"UTC\",\"when\":1}}",
            "{\"time\":{\"between\":[\"08:00\",\"18:00\"],\"zone\":\"UTC\",\"days\":[\"MONDAY\"]}}",
            "[\"principal.region\"]",
            "not json",
            "{\"principal.region\":{\"eq\":\"EU\"}"
    })
    void unknownOrMalformedInputFailsClosed(String json) {
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(ConditionParseException.class);
    }

    @Test
    void oversizedInputIsRejected() {
        String big = "{\"principal.region\":{\"eq\":\"" + "x".repeat(ConditionParser.MAX_JSON_LENGTH) + "\"}}";
        assertThatThrownBy(() -> parser.parse(big)).isInstanceOf(ConditionParseException.class);
    }

    @Test
    void blankConditionsAreEmptyAndAlwaysTrue() {
        assertThat(parser.parse(null).test(at("2026-09-28T10:00:00Z"))).isTrue();
        assertThat(parser.parse("  ").conditions()).isEmpty();
    }

    @Test
    void evaluatorCachesAndReportsInvalid() {
        ParsingGrantConditionEvaluator evaluator =
                new ParsingGrantConditionEvaluator(new TestFixtures.MutableClock(Instant.parse("2026-09-28T10:00:00Z")));
        UUID grant = UUID.randomUUID();
        assertThat(evaluator.evaluate(grant, "{\"principal.region\":{\"eq\":\"EU\"}}", at("2026-09-28T10:00:00Z")))
                .isEqualTo(GrantConditionEvaluator.Result.SATISFIED);
        assertThat(evaluator.evaluate(grant, "{\"principal.region\":{\"eq\":\"US\"}}", at("2026-09-28T10:00:00Z")))
                .isEqualTo(GrantConditionEvaluator.Result.NOT_SATISFIED);
        assertThat(evaluator.evaluate(grant, "{\"principal.region\":{\"like\":\"E%\"}}", at("2026-09-28T10:00:00Z")))
                .isEqualTo(GrantConditionEvaluator.Result.INVALID);
    }
}
