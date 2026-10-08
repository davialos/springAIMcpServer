package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.cel.RuleCompilationException;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.support.SampleTenant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterLibraryTest {

    private final ParameterLibrary library = new ParameterLibrary(SampleTenant.parameters());

    @Test
    void objectDotAttributeIsATypedCelVariable() throws Exception {
        var compiled = library.compileBoolean("customer.age >= 18 && loan.amount < 500000.0");

        assertThat(compiled.referenced()).extracting(Parameter::celName)
                .containsExactlyInAnyOrder("customer.age", "loan.amount");
    }

    @Test
    void standardMacrosAreAvailable() throws Exception {
        // has(), all(), exists(), exists_one(), map() and filter() were rejected before the macros were enabled (ADR-0030)
        for (String macro : new String[]{
                "[18, 30].exists(x, x == customer.age)", "[18, 30].all(x, x <= customer.age)",
                "[18, 30].exists_one(x, x == customer.age)", "[18, 30].map(x, x + 1).exists(y, y > customer.age)",
                "[18, 30].filter(x, x < customer.age).size() >= 0"}) {
            assertThat(library.compileBoolean(macro).referenced()).extracting(Parameter::celName).contains("customer.age");
        }
    }

    @Test
    void anUnknownParameterIsRejectedAtSaveTime() {
        assertThatThrownBy(() -> library.compileBoolean("customer.shoeSize > 40"))
                .isInstanceOf(RuleCompilationException.class)
                .hasMessageContaining("customer.shoeSize");
    }

    @Test
    void aTypeErrorIsRejectedAtSaveTime() {
        assertThatThrownBy(() -> library.compileBoolean("customer.age == \"adult\""))
                .isInstanceOf(RuleCompilationException.class);
    }

    @Test
    void aRuleMustReturnBoolean() {
        assertThatThrownBy(() -> library.compileBoolean("customer.age + 1"))
                .isInstanceOf(RuleCompilationException.class);
    }

    @Test
    void aSyntaxErrorIsReported() {
        assertThatThrownBy(() -> library.compileBoolean("customer.age >="))
                .isInstanceOf(RuleCompilationException.class);
    }

    @Test
    void anOverlongExpressionIsRejected() {
        String huge = "customer.age >= 18" + " && customer.age >= 18".repeat(500);
        assertThatThrownBy(() -> library.compileBoolean(huge)).isInstanceOf(RuleCompilationException.class);
    }

    @Test
    void recipientExpressionsMustBeStrings() throws Exception {
        assertThat(library.compileString("customer.email").referenced()).hasSize(1);
        assertThatThrownBy(() -> library.compileString("customer.age")).isInstanceOf(RuleCompilationException.class);
    }

    @Test
    void celBuiltInsAreAvailableToRules() throws Exception {
        assertThat(library.compileBoolean("customer.country in [\"IN\", \"TH\"] && customer.email.endsWith(\".com\")")
                .referenced()).hasSize(2);
    }
}
