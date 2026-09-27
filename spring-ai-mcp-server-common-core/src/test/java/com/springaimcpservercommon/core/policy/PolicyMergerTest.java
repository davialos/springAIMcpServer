package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.catalog.PolicyLayer;
import com.springaimcpservercommon.core.catalog.RelationPath;
import com.springaimcpservercommon.core.catalog.ScanIssue;
import com.springaimcpservercommon.core.catalog.ScanIssueCode;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.springaimcpservercommon.core.policy.CatalogFixtures.CUSTOMER;
import static com.springaimcpservercommon.core.policy.CatalogFixtures.DELETE_USER;
import static com.springaimcpservercommon.core.policy.CatalogFixtures.DISCOUNT;
import static com.springaimcpservercommon.core.policy.CatalogFixtures.EMAIL;
import static com.springaimcpservercommon.core.policy.CatalogFixtures.FIND_ORDERS;
import static com.springaimcpservercommon.core.policy.CatalogFixtures.FIND_ORDERS_LIMITED;
import static com.springaimcpservercommon.core.policy.CatalogFixtures.ORDER;
import static com.springaimcpservercommon.core.policy.CatalogFixtures.TAX_ID;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyMergerTest {

    private final ScannedCatalog scanned = CatalogFixtures.catalog();
    private final PolicyMerger merger = new PolicyMerger(200, false);

    private static PolicyOverride override(Boolean enabled, String description, Classification classification,
                                           Integer maxLimit, Boolean readOnly) {
        return new PolicyOverride(enabled, enabled != null && !enabled ? "because" : null, description, null, null,
                classification, maxLimit, null, readOnly, false);
    }

    private static PolicyLayerInput doc(PolicyLayer layer, String source, Map<PolicyKey, PolicyOverride> overrides) {
        return new PolicyLayerInput.DocumentLayer(layer, source, new PolicyDocument(1, overrides));
    }

    private static PolicyKey key(CatalogElementRef ref) {
        return new PolicyKey.Canonical(ref);
    }

    private EffectiveCatalog merge(PolicyLayerInput... layers) {
        return merger.merge(scanned, List.of(layers), 7);
    }

    @Test
    void codeOnlyCatalogIsFullyEnabled() {
        EffectiveCatalog c = merge();
        assertThat(c.generation()).isEqualTo(7);
        assertThat(c.scanFingerprint()).isEqualTo(scanned.scanFingerprint());
        assertThat(c.enabledOperations()).hasSize(4);
        assertThat(c.failClosed()).isFalse();
        EffectiveEntity customer = c.entity(CUSTOMER).orElseThrow();
        assertThat(customer.maxLimit()).isEqualTo(50);
        assertThat(customer.attribute("taxId").orElseThrow().classification()).isEqualTo(Classification.INTERNAL);
        assertThat(c.operation(FIND_ORDERS).orElseThrow().maxLimit()).isEqualTo(200);
    }

    @Test
    void enabledIsAndAcrossLayersWithProvenance() {
        EffectiveCatalog c = merge(
                doc(PolicyLayer.FILE, "file:a.json", Map.of(key(DELETE_USER), PolicyOverride.disable("risky"))),
                doc(PolicyLayer.OVERLAY, "rev-1", Map.of(key(DELETE_USER), override(true, null, null, null, null))));
        EffectiveOperation op = c.operation(DELETE_USER).orElseThrow();
        assertThat(op.enabled()).isFalse();
        assertThat(op.provenance()).singleElement().satisfies(p -> {
            assertThat(p.layer()).isEqualTo(PolicyLayer.FILE);
            assertThat(p.source()).isEqualTo("file:a.json");
            assertThat(p.reason()).isEqualTo("risky");
        });
    }

    @Test
    void descriptionLatestLayerWinsAndToolNameStaysCodeOnly() {
        EffectiveCatalog c = merge(
                doc(PolicyLayer.OVERLAY, "rev-2", Map.of(key(DISCOUNT), override(null, "overlay text", null, null, null))),
                doc(PolicyLayer.FILE, "file", Map.of(key(DISCOUNT), override(null, "file text", null, null, null))));
        EffectiveOperation op = c.operation(DISCOUNT).orElseThrow();
        assertThat(op.description()).isEqualTo("overlay text");
        assertThat(op.toolName()).isEqualTo("calculate_discount");
        assertThat(op.descriptor().intent()).isEqualTo("Intent of calculate_discount");
    }

    @Test
    void readOnlyCanOnlyTighten() {
        EffectiveCatalog c = merge(doc(PolicyLayer.FILE, "file", Map.of(
                key(DELETE_USER), override(null, null, null, null, true),
                key(DISCOUNT), override(null, null, null, null, false))));
        assertThat(c.operation(DELETE_USER).orElseThrow().enabled()).isFalse();
        assertThat(c.operation(DELETE_USER).orElseThrow().readOnly()).isFalse();
        EffectiveOperation discount = c.operation(DISCOUNT).orElseThrow();
        assertThat(discount.readOnly()).isTrue();
        assertThat(discount.enabled()).isTrue();
        assertThat(c.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo(ScanIssueCode.POLICY_READ_ONLY_LOOSENING));
    }

    @Test
    void classificationIsMaxAndDeclassifyNeedsOverlayFlag() {
        EffectiveCatalog raised = merge(doc(PolicyLayer.FILE, "file", Map.of(
                key(CUSTOMER), override(null, null, Classification.RESTRICTED, null, null),
                key(ORDER), override(null, null, Classification.PUBLIC, null, null))));
        assertThat(raised.entity(CUSTOMER).orElseThrow().classification()).isEqualTo(Classification.RESTRICTED);
        // INHERIT attribute follows the effective entity, explicit attribute keeps its own
        assertThat(raised.entity(CUSTOMER).orElseThrow().attributes().get(TAX_ID).classification())
                .isEqualTo(Classification.RESTRICTED);
        assertThat(raised.entity(CUSTOMER).orElseThrow().attributes().get(EMAIL).classification())
                .isEqualTo(Classification.PUBLIC);
        assertThat(raised.entity(ORDER).orElseThrow().classification()).isEqualTo(Classification.CONFIDENTIAL);
        assertThat(raised.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo(ScanIssueCode.POLICY_DECLASSIFY_REJECTED));

        PolicyOverride declassify = new PolicyOverride(null, "approved in CR-12", null, null, null,
                Classification.INTERNAL, null, null, null, true);
        EffectiveCatalog lowered = merge(doc(PolicyLayer.OVERLAY, "rev-3", Map.of(key(ORDER), declassify)));
        assertThat(lowered.entity(ORDER).orElseThrow().classification()).isEqualTo(Classification.INTERNAL);
    }

    @Test
    void sensitivityOnlyRisesUnlessDeclassified() {
        PolicyOverride sensitive = new PolicyOverride(null, null, null, null, true, null, null, null, null, false);
        PolicyOverride unsensitive = new PolicyOverride(null, null, null, null, false, null, null, null, null, false);
        EffectiveCatalog c = merge(
                doc(PolicyLayer.FILE, "file", Map.of(key(TAX_ID), sensitive)),
                doc(PolicyLayer.OVERLAY, "rev", Map.of(key(TAX_ID), unsensitive)));
        assertThat(c.entity(CUSTOMER).orElseThrow().attributes().get(TAX_ID).sensitive()).isTrue();
        assertThat(c.entity(CUSTOMER).orElseThrow().attributes().get(TAX_ID).exposable()).isFalse();

        PolicyOverride declassify = new PolicyOverride(null, "ok", null, null, false, null, null, null, null, true);
        EffectiveCatalog d = merge(
                doc(PolicyLayer.FILE, "file", Map.of(key(TAX_ID), sensitive)),
                doc(PolicyLayer.OVERLAY, "rev", Map.of(key(TAX_ID), declassify)));
        assertThat(d.entity(CUSTOMER).orElseThrow().attributes().get(TAX_ID).sensitive()).isFalse();
    }

    @Test
    void maxLimitIsMinAndMandatoryFiltersAreUnion() {
        PolicyOverride f1 = new PolicyOverride(null, null, null, null, null, null, 20, List.of("tenantId"), null, false);
        PolicyOverride f2 = new PolicyOverride(null, null, null, null, null, null, 30, List.of("region"), null, false);
        EffectiveCatalog c = merge(doc(PolicyLayer.FILE, "file", Map.of(key(CUSTOMER), f1)),
                doc(PolicyLayer.OVERLAY, "rev", Map.of(key(CUSTOMER), f2)));
        EffectiveEntity customer = c.entity(CUSTOMER).orElseThrow();
        assertThat(customer.maxLimit()).isEqualTo(20);
        assertThat(customer.mandatoryFilters()).containsExactly("region", "tenantId");
        assertThat(new PolicyMerger(10, false).merge(scanned, List.of(), 1).entity(CUSTOMER).orElseThrow().maxLimit())
                .isEqualTo(10);
    }

    @Test
    void unknownRefsAreWarningsOrErrorsInStrictMode() {
        Map<PolicyKey, PolicyOverride> overrides = Map.of(
                key(CatalogElementRef.entity("com.host.app.Nope")), PolicyOverride.disable("x"));
        EffectiveCatalog lenient = merge(doc(PolicyLayer.FILE, "file", overrides));
        assertThat(lenient.issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo(ScanIssueCode.POLICY_REF_UNKNOWN);
            assertThat(i.severity()).isEqualTo(ScanIssue.Severity.WARNING);
        });
        assertThat(lenient.enabledOperations()).hasSize(4);
        EffectiveCatalog strict = new PolicyMerger(200, true)
                .merge(scanned, List.of(doc(PolicyLayer.FILE, "file", overrides)), 1);
        assertThat(strict.issues()).singleElement()
                .satisfies(i -> assertThat(i.severity()).isEqualTo(ScanIssue.Severity.ERROR));
    }

    @Test
    void unambiguousShorthandApplies() {
        EffectiveCatalog c = merge(doc(PolicyLayer.FILE, "file", Map.of(
                new PolicyKey.Shorthand("com.host.app.OrderService", "calculateDiscount"),
                override(null, "Calculates B2B discounts", null, null, null))));
        assertThat(c.operation(DISCOUNT).orElseThrow().description()).isEqualTo("Calculates B2B discounts");
    }

    @Test
    void overloadedShorthandIsRejectedListingCanonicalRefsAndFailsClosed() {
        EffectiveCatalog c = merge(doc(PolicyLayer.FILE, "file:policy.json", Map.of(
                new PolicyKey.Shorthand("com.host.app.OrderService", "findOrders"),
                override(null, "desc", null, null, null))));
        assertThat(c.issues()).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo(ScanIssueCode.POLICY_SHORTHAND_AMBIGUOUS);
            assertThat(i.message()).contains(FIND_ORDERS.toString()).contains(FIND_ORDERS_LIMITED.toString());
        });
        assertThat(c.enabledOperations()).isEmpty();
        assertThat(c.failClosed()).isTrue();
    }

    @Test
    void invalidFileLayerDisablesEverythingWithProvenance() {
        PolicyLayerInput invalid = new PolicyDocumentParser().parseLayer(PolicyLayer.FILE, "file:/config/p.json",
                "{\"schemaVersion\":1,\"overrides\":{\"op:x.Y#z()\":{\"enabled\":false}}}");
        EffectiveCatalog c = merge(invalid);
        assertThat(c.failClosed()).isTrue();
        assertThat(c.enabledOperations()).isEmpty();
        assertThat(c.entities().values()).noneMatch(EffectiveEntity::enabled);
        assertThat(c.operation(FIND_ORDERS).orElseThrow().provenance()).singleElement().satisfies(p -> {
            assertThat(p.layer()).isEqualTo(PolicyLayer.FILE);
            assertThat(p.value()).contains("fail closed");
            assertThat(p.reason()).contains("file:/config/p.json");
        });
        assertThat(c.layers()).singleElement().satisfies(s -> assertThat(s.valid()).isFalse());
        assertThat(c.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo(ScanIssueCode.POLICY_LAYER_INVALID));
    }

    @Test
    void killSwitchesDisableByToolNameEntityAndGlobally() {
        EffectiveCatalog byTool = merge(new PolicyLayerInput.KillSwitchLayer("ks-1", false, Set.of("calculate_discount"),
                Set.of(), "INC-1"));
        assertThat(byTool.operation(DISCOUNT).orElseThrow().enabled()).isFalse();
        assertThat(byTool.enabledOperations()).hasSize(3);

        EffectiveCatalog byEntity = merge(new PolicyLayerInput.KillSwitchLayer("ks-2", false, Set.of(), Set.of(ORDER),
                "INC-2"));
        assertThat(byEntity.entity(ORDER).orElseThrow().enabled()).isFalse();
        assertThat(byEntity.operation(FIND_ORDERS).orElseThrow().enabled()).isFalse();
        assertThat(byEntity.operation(FIND_ORDERS_LIMITED).orElseThrow().enabled()).isFalse();
        assertThat(byEntity.operation(DISCOUNT).orElseThrow().enabled()).isTrue();

        EffectiveCatalog global = merge(new PolicyLayerInput.KillSwitchLayer("ks-3", true, Set.of(), Set.of(), "INC-3"));
        assertThat(global.enabledOperations()).isEmpty();
    }

    @Test
    void policyFingerprintChangesWithLayers() {
        String none = merge().policyFingerprint();
        String one = merge(doc(PolicyLayer.FILE, "file", Map.of(key(DELETE_USER), PolicyOverride.disable("x"))))
                .policyFingerprint();
        assertThat(none).startsWith("sha256:").isNotEqualTo(one);
    }

    @Test
    void relationPathsFollowEnabledEntities() {
        EffectiveCatalog c = merge();
        List<RelationPath> paths = c.relationPaths(CUSTOMER, 2);
        assertThat(paths).singleElement().satisfies(p -> {
            assertThat(p.expression()).isEqualTo("orders");
            assertThat(p.to()).isEqualTo(ORDER);
        });
        EffectiveCatalog killed = merge(new PolicyLayerInput.KillSwitchLayer("ks", false, Set.of(), Set.of(ORDER), "x"));
        assertThat(killed.relationPaths(CUSTOMER, 2)).isEmpty();
    }
}
