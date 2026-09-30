package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.config.ResourceKind;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSpecsTest {

    private final List<FieldViolation> errors = new ArrayList<>();

    @Test
    void acceptsAWellFormedObject() {
        assertThat(ResourceSpecs.validate("{\"systemPrompt\":\"Help the user.\",\"tools\":[]}", "specJson", errors))
                .isNotNull();
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsMissingBlankArrayMalformedAndOversizedSpecs() {
        assertThat(ResourceSpecs.validate(null, "specJson", errors)).isNull();
        assertThat(ResourceSpecs.validate("  ", "specJson", errors)).isNull();
        assertThat(ResourceSpecs.validate("[1,2]", "specJson", errors)).isNull();
        assertThat(ResourceSpecs.validate("{not json", "specJson", errors)).isNull();
        assertThat(ResourceSpecs.validate("{\"a\":\"" + "x".repeat(ResourceSpecs.MAX_CHARS) + "\"}", "specJson", errors))
                .isNull();
        assertThat(errors).hasSize(5).allMatch(v -> v.field().equals("specJson"));
    }

    @Test
    void rejectsCredentialsWithoutEchoingThem() {
        String spec = "{\"systemPrompt\":\"use key sk-abcdefghijklmnopqrstuvwxyz123456 to call\"}";

        assertThat(ResourceSpecs.validate(spec, "specJson", errors)).isNull();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("credential").doesNotContain("sk-abcdef");
    }

    @Test
    void everyKindMapsToAnAuthorAndPublishPermission() {
        for (ResourceKind kind : ResourceKind.values()) {
            assertThat(ResourceAdminController.authorPermission(kind)).isNotNull();
            assertThat(ResourceAdminController.publishPermission(kind)).isNotNull();
        }
        assertThat(ResourceAdminController.authorPermission(ResourceKind.AGENT)).isEqualTo(Permission.AGENT_AUTHOR);
        assertThat(ResourceAdminController.publishPermission(ResourceKind.QUERY)).isEqualTo(Permission.QUERY_PUBLISH);
        assertThat(ResourceAdminController.authorPermission(ResourceKind.ROW_POLICY))
                .isEqualTo(Permission.WORKSPACE_ADMIN);
    }
}
