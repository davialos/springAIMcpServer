package com.springaimcpservercommon.celfaker;

import com.springaimcpservercommon.celfaker.contract.ApiContract;
import com.springaimcpservercommon.celfaker.pipeline.FakerPipeline;
import com.springaimcpservercommon.celfaker.payload.JsonValues;
import com.springaimcpservercommon.celfaker.workflow.Inject;
import com.springaimcpservercommon.celfaker.workflow.Step;
import com.springaimcpservercommon.celfaker.workflow.Workflow;
import com.springaimcpservercommon.celfaker.workflow.WorkflowPlanner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineTest {

    static ApiContract shop() throws IOException {
        try (InputStream in = PipelineTest.class.getResourceAsStream("/celfaker/examples/shop-contract.json")) {
            return ApiContract.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void fullRunProducesLinkedFiles() throws IOException {
        FakerPipeline.Output out = FakerPipeline.run(FakerPipeline.Request.of(shop()));
        assertThat(out.files()).containsKeys("parameters.json", "attribute-map.json", "expressions.json", "cel-cases.json",
                "summary.json", "k6/main.js", "k6/lib/runtime.js", "k6/workflow.json", "k6/apis.json", "k6/README.md",
                "k6/data/createCustomer.valid.json", "k6/data/createCustomer.invalid.json", "k6/data/createOrder.valid.json");
        assertThat(out.warnings()).isEmpty();

        // every valid customer satisfies the CEL rules (age range), every rule-violation case breaks one
        JsonNode valid = JsonValues.MAPPER.readTree(out.files().get("k6/data/createCustomer.valid.json"));
        assertThat(valid).hasSize(30);
        valid.forEach(b -> assertThat(b.path("age").asLong()).isBetween(18L, 120L));
        JsonNode invalid = JsonValues.MAPPER.readTree(out.files().get("k6/data/createCustomer.invalid.json"));
        long ruleViolations = 0;
        for (JsonNode c : invalid) {
            if (c.path("reason").asString().startsWith("rule:customer.age")) {
                long age = c.path("body").path("age").asLong();
                assertThat(age < 18 || age > 120).isTrue();
                ruleViolations++;
            }
        }
        assertThat(ruleViolations).isGreaterThan(0);
        assertThat(invalid.toString()).contains("customer.age:invalid_string", "customer.email:missing");

        // the attribute map is the linked JSON file: names match parameters.json
        JsonNode params = JsonValues.MAPPER.readTree(out.files().get("parameters.json"));
        JsonNode map = JsonValues.MAPPER.readTree(out.files().get("attribute-map.json")).path("attributes");
        params.forEach(p -> assertThat(map.has(p.path("name").asString())).isTrue());
        assertThat(out.files().get("k6/main.js")).contains("createOrder", "negative", "workflow_ok");
        assertThat(out.files().get("k6/workflow.json")).contains("{{createOrder.id}}");
    }

    @Test
    void sameSeedGivesSameFiles() throws IOException {
        var a = FakerPipeline.run(FakerPipeline.Request.of(shop())).files();
        var b = FakerPipeline.run(FakerPipeline.Request.of(shop())).files();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void proposedWorkflowChainsActionIntoValidation() throws IOException {
        Workflow w = WorkflowPlanner.propose(shop());
        Step validate = w.steps().stream().filter(s -> s.id().equals("validateOrder")).findFirst().orElseThrow();
        assertThat(validate.dependsOn()).containsExactly("createOrder");
        assertThat(validate.inject()).containsExactly(new Inject("path.id", "{{createOrder.id}}"));
        assertThat(WorkflowPlanner.validate(w, shop())).isEmpty();
    }

    @Test
    void validatorFindsBrokenFlows() throws IOException {
        ApiContract c = shop();
        Workflow w = Workflow.fromJson("""
                {"name":"bad","steps":[
                  {"id":"a","api":"createOrder","dependsOn":["b"]},
                  {"id":"b","api":"validateOrder","dependsOn":["a"]}]}""");
        assertThat(WorkflowPlanner.validate(w, c)).anyMatch(p -> p.contains("cycle"));
        Workflow w2 = Workflow.fromJson("""
                {"name":"bad","steps":[
                  {"id":"a","api":"createOrder"},
                  {"id":"v","api":"validateOrder","dependsOn":["a"],"inject":[{"target":"path.id","value":"{{a.nope}}"}]},
                  {"id":"x","api":"missing"}]}""");
        List<String> problems = WorkflowPlanner.validate(w2, c);
        assertThat(problems).anyMatch(p -> p.contains("does not extract nope")).anyMatch(p -> p.contains("unknown api missing"));
        assertThatThrownBy(() -> com.springaimcpservercommon.celfaker.k6.K6WorkflowGenerator.generate(c, w2, java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
