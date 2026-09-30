package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ArgConstraint;
import com.springaimcpservercommon.ai.tool.ToolBinding;
import com.springaimcpservercommon.ai.tool.ToolSource;
import com.springaimcpservercommon.ai.tool.WriteMode;
import com.springaimcpservercommon.persistence.config.PublishedResource;
import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.persistence.config.ResourceStatus;
import com.springaimcpservercommon.persistence.config.RevisionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolBindingSpecsTest {

    private static PublishedResource resource(String spec) {
        return new PublishedResource(UUID.randomUUID(), UUID.randomUUID(), ResourceKind.TOOL_BINDING, "find-orders",
                ResourceStatus.ACTIVE, null, UUID.randomUUID(), 3, RevisionState.PUBLISHED, spec, 1,
                "sha256:" + "0".repeat(64), null);
    }

    @Test
    void aFullSpecBecomesABindingWithTheResourceIdentity() {
        UUID query = UUID.randomUUID();
        PublishedResource r = resource("""
                {"toolName":"find_orders","description":"Find orders",
                 "source":{"kind":"query","ref":"%s"},
                 "writeMode":"execute",
                 "argConstraints":{"customerId":{"kind":"principalAttr","attr":"customerId"},
                                   "status":{"kind":"literal","value":"OPEN"},
                                   "limit":{"kind":"range","min":1,"max":50}},
                 "timeoutSeconds":10,"maxCallsPerTurn":2,
                 "result":{"maxChars":4000,"maskSensitive":false},"mcpExposed":true}
                """.formatted(query));

        ToolBinding b = ToolBindingSpecs.parse(r);

        assertThat(b.id()).isEqualTo(r.resourceId());
        assertThat(b.revision()).isEqualTo(3);
        assertThat(b.workspaceId()).isEqualTo(r.workspaceId());
        assertThat(b.toolName()).isEqualTo("find_orders");
        assertThat(b.descriptionOverride()).isEqualTo("Find orders");
        assertThat(b.source()).isEqualTo(new ToolSource.QuerySource(query));
        assertThat(b.writeMode()).isEqualTo(WriteMode.EXECUTE);
        assertThat(b.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(b.maxCallsPerTurn()).isEqualTo(2);
        assertThat(b.result().maxChars()).isEqualTo(4000);
        assertThat(b.result().maskSensitive()).isFalse();
        assertThat(b.mcpExposed()).isTrue();
        assertThat(b.argConstraints()).containsOnlyKeys("customerId", "status", "limit");
        assertThat(b.argConstraints().get("customerId"))
                .isEqualTo(ArgConstraint.principalAttr("customerId"));
    }

    @Test
    void defaultsAreSafe() {
        ToolBinding b = ToolBindingSpecs.parse(resource(
                "{\"toolName\":\"find_orders\",\"source\":{\"kind\":\"operation\",\"ref\":\"op:com.acme.Svc#find(java.lang.String)\"}}"));

        assertThat(b.writeMode()).isEqualTo(WriteMode.EXECUTE);
        assertThat(b.mcpExposed()).isFalse();
        assertThat(b.maxCallsPerTurn()).isEqualTo(5);
        assertThat(b.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(b.result().maskSensitive()).isTrue();
        assertThat(b.source()).isInstanceOf(ToolSource.OperationSource.class);
    }

    @Test
    void invalidSpecsAreRejectedWithoutEchoingValues() {
        assertThatThrownBy(() -> ToolBindingSpecs.parse(resource("{oops"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolBindingSpecs.parse(resource("{\"source\":{}}")))
                .hasMessageContaining("toolName");
        assertThatThrownBy(() -> ToolBindingSpecs.parse(resource(
                "{\"toolName\":\"Bad Name\",\"source\":{\"kind\":\"query\",\"ref\":\"" + UUID.randomUUID() + "\"}}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolBindingSpecs.parse(resource(
                "{\"toolName\":\"find_orders\",\"source\":{\"kind\":\"shell\",\"ref\":\"rm -rf\"}}")))
                .hasMessageNotContaining("rm -rf");
        assertThatThrownBy(() -> ToolBindingSpecs.parse(resource(
                "{\"toolName\":\"find_orders\",\"maxCallsPerTurn\":9999,\"source\":{\"kind\":\"query\",\"ref\":\""
                        + UUID.randomUUID() + "\"}}"))).hasMessageContaining("maxCallsPerTurn");
    }

    @Test
    void aProposingToolNeedsAnOperationAndMayNameItsChangeKind() {
        ToolBinding delete = ToolBindingSpecs.parse(resource("""
                {"toolName":"delete_order","writeMode":"PROPOSE","change":"delete",
                 "source":{"kind":"operation","ref":"op:com.acme.Svc#delete(java.lang.Long)"}}
                """));
        assertThat(delete.writeMode()).isEqualTo(WriteMode.PROPOSE);
        assertThat(delete.change()).isEqualTo(com.springaimcpservercommon.ai.tool.ProposalService.Change.DELETE);

        assertThat(ToolBindingSpecs.parse(resource(
                "{\"toolName\":\"find_orders\",\"source\":{\"kind\":\"query\",\"ref\":\"" + UUID.randomUUID()
                        + "\"}}")).change()).isNull();
        assertThatThrownBy(() -> ToolBindingSpecs.parse(resource(
                "{\"toolName\":\"find_orders\",\"writeMode\":\"PROPOSE\",\"source\":{\"kind\":\"query\","
                        + "\"ref\":\"" + UUID.randomUUID() + "\"}}"))).hasMessageContaining("operation source");
        assertThatThrownBy(() -> ToolBindingSpecs.parse(resource("""
                {"toolName":"delete_order","writeMode":"PROPOSE","change":"explode",
                 "source":{"kind":"operation","ref":"op:com.acme.Svc#delete(java.lang.Long)"}}
                """))).hasMessageContaining("change").hasMessageNotContaining("explode");
    }
}
