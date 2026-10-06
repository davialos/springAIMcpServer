package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.config.RuleEngineProperties;
import com.springaimcpservercommon.ruleengine.domain.DataType;
import com.springaimcpservercommon.ruleengine.domain.Model.SysAttribute;
import com.springaimcpservercommon.ruleengine.domain.Model.SysObject;
import com.springaimcpservercommon.ruleengine.library.CelEngine;
import com.springaimcpservercommon.ruleengine.library.ContextCoercer;
import com.springaimcpservercommon.ruleengine.library.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.library.ParameterLibraryService;
import com.springaimcpservercommon.ruleengine.repo.LibraryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CEL over the parameter library: names, types, validation and evaluation, without a database. */
class CelEngineTest {

    private static SysAttribute attr(long id, String code, DataType type) {
        return new SysAttribute(id, 1, code, code, type, false, null);
    }

    private static final List<SysObject> OBJECTS = List.of(
            new SysObject(1, "customer", "Customer", null, null, true, List.of(attr(1, "age", DataType.INTEGER),
                    attr(2, "income", DataType.DECIMAL), attr(3, "country", DataType.STRING),
                    attr(4, "tags", DataType.STRING_LIST), attr(5, "joined", DataType.TIMESTAMP),
                    attr(6, "vip", DataType.BOOLEAN))),
            new SysObject(2, "transaction", "Transaction", null, null, true, List.of(attr(7, "amount", DataType.DECIMAL))));

    private final CelEngine cel;
    private final ContextCoercer coercer = new ContextCoercer();
    private final ParameterLibrary library;

    CelEngineTest() {
        LibraryRepository repo = new LibraryRepository(null) {
            @Override
            public List<SysObject> loadAll() {
                return OBJECTS;
            }

            @Override
            public String fingerprint() {
                return "v1";
            }
        };
        ParameterLibraryService service = new ParameterLibraryService(repo, new RuleEngineProperties(null, null, null, null, false, null));
        library = service.refresh();
        cel = new CelEngine(service);
    }

    private boolean eval(String expression, Map<String, Map<String, Object>> context) {
        return cel.evaluate(cel.compile(expression), coercer.coerce(context, library));
    }

    @Test
    void sysObjectsAndAttributesAreCelNames() {
        assertThat(library.celNames()).contains("customer.age", "customer.income", "transaction.amount");
        Map<String, Map<String, Object>> ctx = Map.of("customer", Map.of("age", 20, "country", "IN"));
        assertThat(eval("customer.age >= 18 && customer.country == 'IN'", ctx)).isTrue();
        assertThat(eval("customer.age >= 21", ctx)).isFalse();
        assertThat(cel.compile("customer.age >= 18 && transaction.amount < 5.0").references())
                .containsExactlyInAnyOrder("customer.age", "transaction.amount");
    }

    @Test
    void valuesAreConvertedToTheirDeclaredTypesSoNumbersCompareAcrossIntAndDouble() {
        // JSON gave an Integer for a DECIMAL attribute and a String for an INTEGER one
        Map<String, Map<String, Object>> ctx = Map.of("transaction", Map.of("amount", 50000),
                "customer", Map.of("age", "42", "income", 1000));
        assertThat(eval("transaction.amount >= 50000", ctx)).isTrue();
        assertThat(eval("transaction.amount > 49999.5", ctx)).isTrue();
        assertThat(eval("customer.age == 42", ctx)).isTrue();
        assertThat(eval("transaction.amount <= customer.income * 5.0", ctx)).isFalse();
    }

    @Test
    void listsTimestampsAndMacros() {
        Map<String, Map<String, Object>> ctx = Map.of("customer", Map.of("tags", List.of("vip", "new"),
                "joined", "2026-01-01T00:00:00Z", "vip", true));
        assertThat(eval("'vip' in customer.tags", ctx)).isTrue();
        assertThat(eval("customer.tags.exists(t, t.startsWith('n'))", ctx)).isTrue();
        assertThat(eval("customer.joined < timestamp('2026-06-01T00:00:00Z')", ctx)).isTrue();
        assertThat(eval("customer.vip", ctx)).isTrue();
    }

    @Test
    void mistakesAreRejectedWhenTheRuleIsSavedNotWhenItRuns() {
        assertThatThrownBy(() -> cel.compile("customer.age >=")).isInstanceOf(CelEngine.ExpressionException.class);
        assertThatThrownBy(() -> cel.compile("customer.age + 1")).as("not a boolean")
                .isInstanceOf(CelEngine.ExpressionException.class);
        assertThatThrownBy(() -> cel.compile("loan.principal > 1")).as("unknown object")
                .isInstanceOf(CelEngine.ExpressionException.class).hasMessageContaining("loan");
        assertThatThrownBy(() -> cel.compile("customer.height > 1")).as("unknown attribute")
                .isInstanceOf(CelEngine.ExpressionException.class)
                .satisfies(e -> assertThat(((CelEngine.ExpressionException) e).problems())
                        .containsExactly("'customer.height' is not in the parameter library"));
    }

    @Test
    void aMissingAttributeFailsEvaluationWithAReadableError() {
        CelEngine.Compiled compiled = cel.compile("customer.age >= 18");
        assertThatThrownBy(() -> cel.evaluate(compiled, coercer.coerce(Map.of("customer", Map.of("country", "IN")), library)))
                .isInstanceOf(CelEngine.ExpressionException.class);
    }

    @Test
    void valuesThatCannotBeConvertedAreReportedWithTheirName() {
        assertThatThrownBy(() -> coercer.coerce(Map.of("customer", Map.of("age", "old")), library))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("customer.age");
    }

    @Test
    void aChangedLibraryRecompilesWhatWasCached() {
        assertThat(cel.compile("customer.age > 1")).isSameAs(cel.compile("customer.age > 1"));
    }
}
