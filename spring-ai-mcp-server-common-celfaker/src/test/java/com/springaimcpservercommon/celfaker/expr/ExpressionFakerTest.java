package com.springaimcpservercommon.celfaker.expr;

import com.springaimcpservercommon.celfaker.payload.Candidate;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import com.springaimcpservercommon.celfaker.payload.PayloadAnalyzer;
import com.springaimcpservercommon.celfaker.pipeline.FakerLibrary;
import com.springaimcpservercommon.celfaker.values.AttributeValueMap;
import com.springaimcpservercommon.celfaker.values.ValueFactory;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.model.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionFakerTest {

    static final String PAYLOAD = """
            {"id": 42, "email": "ada@example.com", "age": 34, "balance": 120.5, "active": true,
             "createdAt": "2025-03-04T10:15:30Z", "ttl": "PT2H", "tags": ["vip", "beta"], "scores": [3, 5, 8],
             "prices": [1.5, 2.5], "status": "NEW", "attrs": {"tier": "gold"},
             "customer": {"name": "Ada", "address": {"city": "London"}}}
            """;

    private static List<Candidate> candidates() {
        return PayloadAnalyzer.analyze(JsonValues.MAPPER.readTree(PAYLOAD), "order", java.util.Set.of("attrs")).candidates();
    }

    @Test
    void analyzerInfersTypesAndSysObjects() {
        Map<String, DataType> types = candidates().stream().collect(Collectors.toMap(Candidate::celName, Candidate::type));
        assertThat(types).containsEntry("order.id", DataType.INT)
                .containsEntry("order.balance", DataType.DOUBLE)
                .containsEntry("order.active", DataType.BOOL)
                .containsEntry("order.createdAt", DataType.TIMESTAMP)
                .containsEntry("order.ttl", DataType.DURATION)
                .containsEntry("order.tags", DataType.LIST_STRING)
                .containsEntry("order.scores", DataType.LIST_INT)
                .containsEntry("order.prices", DataType.LIST_DOUBLE)
                .containsEntry("order.attrs", DataType.MAP)
                .containsEntry("customer.name", DataType.STRING)
                .containsEntry("customer_address.city", DataType.STRING);
    }

    @Test
    void everyGeneratedExpressionCompilesAndEvaluates() {
        List<Candidate> cs = candidates();
        ParameterLibrary lib = FakerLibrary.library(cs);
        AttributeValueMap map = new ValueFactory(7).build(cs);
        ExpressionFaker.Result r = new ExpressionFaker(lib, map, 7).generate(ExpressionFaker.Options.defaults());
        System.out.println("accepted=" + r.expressions().size() + " rejected=" + r.rejected().size());
        r.rejected().forEach(x -> System.out.println("REJECTED " + x));
        assertThat(r.expressions().size()).isGreaterThan(300);
        assertThat(r.expressions().stream().map(GeneratedExpression::category).distinct().count()).isGreaterThanOrEqualTo(11);
        CaseBuilder cases = new CaseBuilder(lib, map, 7);
        long withTrue = r.expressions().stream().filter(e -> cases.build(e.expression(), 12).stream().anyMatch(CelCase::isTrue)).count();
        assertThat(withTrue).isGreaterThan(r.expressions().size() / 2);
    }

    @Test
    void generationIsDeterministicForASeed() {
        List<Candidate> cs = candidates();
        ParameterLibrary lib = FakerLibrary.library(cs);
        AttributeValueMap map = new ValueFactory(1).build(cs);
        var a = new ExpressionFaker(lib, map, 1).generate(ExpressionFaker.Options.defaults()).expressions();
        var b = new ExpressionFaker(lib, map, 1).generate(ExpressionFaker.Options.defaults()).expressions();
        assertThat(a).isEqualTo(b);
    }
}
