package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ArgConstraintsTest {

    private final DaiPrincipal alice = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice", "Alice",
            Set.of(), Set.of(), Map.of(), Map.of("customerId", "c-42"), Classification.INTERNAL, null, Set.of());

    @Test
    void aPrincipalAttributeOverwritesWhateverTheModelSent() {
        var result = ArgConstraints.apply(Map.of("customerId", ArgConstraint.principalAttr("customerId")), alice,
                "{\"customerId\":\"someone-else\",\"q\":\"x\"}");

        assertThat(result).isEqualTo(new ArgConstraints.Applied("{\"customerId\":\"c-42\",\"q\":\"x\"}"));
    }

    @Test
    void aMissingCallerAttributeRefusesTheCallAndNeverEchoesValues() {
        var result = ArgConstraints.apply(Map.of("region", ArgConstraint.principalAttr("region")), alice, "{}");

        assertThat(result).isInstanceOfSatisfying(ArgConstraints.Rejected.class, r -> {
            assertThat(r.code()).isEqualTo("constraint_unsatisfied");
            assertThat(r.message()).doesNotContain("c-42");
        });
    }

    @Test
    void aLiteralIsForcedEvenWhenTheArgumentIsAbsent() {
        var result = ArgConstraints.apply(Map.of("status", ArgConstraint.literal("OPEN")), alice, "{}");

        assertThat(result).isEqualTo(new ArgConstraints.Applied("{\"status\":\"OPEN\"}"));
    }

    @Test
    void aRangeRefusesOutOfRangeAndNonNumericValuesButLeavesAnAbsentOneAlone() {
        var range = Map.of("limit", ArgConstraint.range(1, 50));

        assertThat(ArgConstraints.apply(range, alice, "{\"limit\":10}"))
                .isEqualTo(new ArgConstraints.Applied("{\"limit\":10}"));
        assertThat(ArgConstraints.apply(range, alice, "{}")).isEqualTo(new ArgConstraints.Applied("{}"));
        assertThat(ArgConstraints.apply(range, alice, "{\"limit\":5000}")).isInstanceOf(ArgConstraints.Rejected.class);
        assertThat(ArgConstraints.apply(range, alice, "{\"limit\":\"ten\"}")).isInstanceOf(ArgConstraints.Rejected.class);
    }

    @Test
    void malformedArgumentsAreRefusedWhenConstraintsExist() {
        var constraints = Map.of("status", ArgConstraint.literal("OPEN"));

        assertThat(ArgConstraints.apply(constraints, alice, "{oops")).isInstanceOf(ArgConstraints.Rejected.class);
        assertThat(ArgConstraints.apply(constraints, alice, "[1]")).isInstanceOf(ArgConstraints.Rejected.class);
    }

    @Test
    void withoutConstraintsTheInputPassesThroughUntouched() {
        assertThat(ArgConstraints.apply(Map.of(), alice, "{ \"a\" : 1 }"))
                .isEqualTo(new ArgConstraints.Applied("{ \"a\" : 1 }"));
    }
}
