package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.PolicyLayer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyDocumentParserTest {

    private final PolicyDocumentParser parser = new PolicyDocumentParser();

    private static final String LLD_EXAMPLE = """
            {
              "schemaVersion": 1,
              "overrides": {
                "op:com.host.app.UserService#deleteUser(java.lang.Long)": {
                  "enabled": false,
                  "reason": "Disabled due to AI hallucination risk."
                },
                "com.host.app.OrderService.calculateDiscount": {
                  "descriptionOverride": "Calculates B2B discounts. Do not use this for retail customers."
                },
                "entity:com.host.app.Customer": { "maxLimit": 20, "mandatoryFilters": ["tenantId"] },
                "attr:com.host.app.Customer#taxId": { "sensitive": true, "classification": "restricted" }
              }
            }
            """;

    @Test
    void parsesTheLldExample() {
        PolicyDocument doc = parser.parse(LLD_EXAMPLE, PolicyLayer.FILE);
        assertThat(doc.overrides()).hasSize(4);
        assertThat(doc.overrides().keySet()).first()
                .isEqualTo(new PolicyKey.Canonical(CatalogFixtures.DELETE_USER));
        assertThat(doc.overrides().get(new PolicyKey.Shorthand("com.host.app.OrderService", "calculateDiscount"))
                .descriptionOverride()).startsWith("Calculates B2B");
        PolicyOverride customer = doc.overrides().get(new PolicyKey.Canonical(CatalogElementRef.entity("com.host.app.Customer")));
        assertThat(customer.maxLimit()).isEqualTo(20);
        assertThat(customer.mandatoryFilters()).containsExactly("tenantId");
        assertThat(doc.overrides().get(new PolicyKey.Canonical(CatalogFixtures.TAX_ID)).classification())
                .isEqualTo(Classification.RESTRICTED);
        assertThat(doc.fingerprint()).isEqualTo(parser.parse(LLD_EXAMPLE, PolicyLayer.FILE).fingerprint());
    }

    @Test
    void rejectsUnknownFieldsAtEveryLevel() {
        assertThatThrownBy(() -> parser.parse("{\"schemaVersion\":1,\"overrides\":{},\"extra\":true}", PolicyLayer.FILE))
                .isInstanceOf(PolicyValidationException.class).hasMessageContaining("unknown field \"extra\"");
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"overrides":{"entity:com.a.B":{"maxLimt":5}}}""", PolicyLayer.FILE))
                .isInstanceOf(PolicyValidationException.class).hasMessageContaining("unknown field \"maxLimt\"");
    }

    @Test
    void requiresReasonWhenDisabling() {
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"overrides":{"op:com.a.B#c()":{"enabled":false}}}""", PolicyLayer.FILE))
                .isInstanceOf(PolicyValidationException.class).hasMessageContaining("reason: is mandatory");
    }

    @Test
    void rejectsWrongVersionTypesNullsAndInherit() {
        PolicyValidationException e = catchValidation("""
                {"schemaVersion":2,"overrides":{"entity:com.a.B":{"maxLimit":"10","enabled":null,
                 "classification":"INHERIT","keywords":[1]}}}""");
        assertThat(e.errors()).anySatisfy(m -> assertThat(m).contains("schemaVersion"))
                .anySatisfy(m -> assertThat(m).contains("maxLimit"))
                .anySatisfy(m -> assertThat(m).contains("null is not allowed"))
                .anySatisfy(m -> assertThat(m).contains("classification"))
                .anySatisfy(m -> assertThat(m).contains("keywords[0]"));
    }

    @Test
    void rejectsInvalidJsonDuplicateKeysAndBadKeys() {
        assertThat(catchValidation("{not json").errors()).singleElement().asString().contains("not valid JSON");
        assertThat(catchValidation("""
                {"schemaVersion":1,"schemaVersion":1,"overrides":{}}""").errors()).isNotEmpty();
        assertThat(catchValidation("""
                {"schemaVersion":1,"overrides":{"table:users":{"enabled":false,"reason":"x"}}}""").errors())
                .singleElement().asString().contains("invalid key");
        assertThat(catchValidation("""
                {"schemaVersion":1,"overrides":{"justAWord":{"readOnly":true}}}""").errors())
                .singleElement().asString().contains("invalid key");
    }

    @Test
    void declassifyIsOnlyAllowedInOverlays() {
        String json = """
                {"schemaVersion":1,"overrides":{"attr:com.a.B#c":{"sensitive":false,"declassify":true}}}""";
        assertThat(catchValidation(json).errors()).singleElement().asString().contains("only allowed in OVERLAY");
        PolicyDocument overlay = parser.parse(json, PolicyLayer.OVERLAY);
        assertThat(overlay.overrides().values()).singleElement().satisfies(o -> assertThat(o.declassify()).isTrue());
    }

    @Test
    void lintsDescriptionsForSecretsAndLength() {
        String secret = """
                {"schemaVersion":1,"overrides":{"op:com.a.B#c()":{"descriptionOverride":"call with token=abcdef123456"}}}""";
        PolicyValidationException e = catchValidation(secret);
        assertThat(e.errors()).singleElement().asString().contains("secret pattern").doesNotContain("abcdef123456");
        String longMeaning = "{\"schemaVersion\":1,\"overrides\":{\"attr:com.a.B#c\":{\"descriptionOverride\":\""
                + "x".repeat(300) + "\"}}}";
        assertThat(catchValidation(longMeaning).errors()).singleElement().asString().contains("exceeds 256");
    }

    @Test
    void parseLayerNeverThrowsAndFailsClosed() {
        PolicyLayerInput input = parser.parseLayer(PolicyLayer.FILE, "classpath:policy.json", "{broken");
        assertThat(input).isInstanceOf(PolicyLayerInput.InvalidLayer.class);
        assertThat(parser.parseLayer(PolicyLayer.FILE, "f", LLD_EXAMPLE)).isInstanceOf(PolicyLayerInput.DocumentLayer.class);
    }

    private PolicyValidationException catchValidation(String json) {
        try {
            parser.parse(json, PolicyLayer.FILE);
        } catch (PolicyValidationException e) {
            return e;
        }
        throw new AssertionError("expected a validation error");
    }
}
