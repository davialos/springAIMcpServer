package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.catalog.PolicyLayer;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property: for random combinations of layers (without declassification), the merged catalog is never less
 * restrictive than what any single layer demands on its own (LLD-03 §9).
 */
class PolicyMergerPropertyTest {

    private static final ScannedCatalog SCANNED = CatalogFixtures.catalog();
    private static final PolicyMerger MERGER = new PolicyMerger(200, false);
    private static final List<PolicyKey> KEYS = List.of(
            new PolicyKey.Canonical(CatalogFixtures.CUSTOMER),
            new PolicyKey.Canonical(CatalogFixtures.ORDER),
            new PolicyKey.Canonical(CatalogFixtures.TAX_ID),
            new PolicyKey.Canonical(CatalogFixtures.EMAIL),
            new PolicyKey.Canonical(CatalogFixtures.FIND_ORDERS),
            new PolicyKey.Canonical(CatalogFixtures.FIND_ORDERS_LIMITED),
            new PolicyKey.Canonical(CatalogFixtures.DISCOUNT),
            new PolicyKey.Canonical(CatalogFixtures.DELETE_USER),
            new PolicyKey.Shorthand("com.host.app.UserService", "deleteUser"),
            new PolicyKey.Shorthand("com.host.app.OrderService", "findOrders"),   // ambiguous: fails closed
            new PolicyKey.Canonical(CatalogElementRef.entity("com.host.app.Unknown")));
    private static final Classification[] CLASSIFICATIONS = {
            Classification.PUBLIC, Classification.INTERNAL, Classification.CONFIDENTIAL, Classification.RESTRICTED};

    @RepeatedTest(300)
    void mergedIsNeverLessRestrictiveThanAnySingleLayer(RepetitionInfo info) {
        Random random = new Random(4711L * info.getCurrentRepetition());
        List<PolicyLayerInput> layers = new ArrayList<>();
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            layers.add(randomLayer(random, i));
        }
        EffectiveCatalog merged = MERGER.merge(SCANNED, layers, 1);
        for (PolicyLayerInput single : layers) {
            assertAtLeastAsRestrictive(merged, MERGER.merge(SCANNED, List.of(single), 1), single);
        }
        assertAtLeastAsRestrictive(merged, MERGER.merge(SCANNED, List.of(), 1), null);
    }

    private static void assertAtLeastAsRestrictive(EffectiveCatalog merged, EffectiveCatalog single,
                                                   PolicyLayerInput layer) {
        String ctx = "layer " + layer;
        for (EffectiveOperation s : single.operations().values()) {
            EffectiveOperation m = merged.operation(s.ref()).orElseThrow();
            if (!s.enabled()) {
                assertThat(m.enabled()).as(ctx + " enabled " + s.ref()).isFalse();
            }
            assertThat(m.classification()).as(ctx + " classification " + s.ref()).isGreaterThanOrEqualTo(s.classification());
            assertThat(m.maxLimit()).as(ctx + " maxLimit " + s.ref()).isLessThanOrEqualTo(s.maxLimit());
            assertThat(m.readOnly()).as(ctx + " readOnly " + s.ref()).isEqualTo(s.descriptor().readOnly());
            assertThat(m.toolName()).isEqualTo(s.descriptor().toolName());
        }
        for (EffectiveEntity s : single.entities().values()) {
            EffectiveEntity m = merged.entity(s.ref()).orElseThrow();
            if (!s.enabled()) {
                assertThat(m.enabled()).as(ctx + " enabled " + s.ref()).isFalse();
            }
            assertThat(m.classification()).as(ctx + " classification " + s.ref()).isGreaterThanOrEqualTo(s.classification());
            assertThat(m.maxLimit()).as(ctx + " maxLimit " + s.ref()).isLessThanOrEqualTo(s.maxLimit());
            assertThat(m.mandatoryFilters()).as(ctx + " filters " + s.ref()).containsAll(s.mandatoryFilters());
            for (EffectiveAttribute sa : s.attributes().values()) {
                EffectiveAttribute ma = m.attributes().get(sa.ref());
                if (sa.sensitive()) {
                    assertThat(ma.sensitive()).as(ctx + " sensitive " + sa.ref()).isTrue();
                }
                if (!sa.enabled()) {
                    assertThat(ma.enabled()).as(ctx + " enabled " + sa.ref()).isFalse();
                }
                assertThat(ma.classification()).as(ctx + " classification " + sa.ref())
                        .isGreaterThanOrEqualTo(sa.classification());
            }
        }
    }

    private static PolicyLayerInput randomLayer(Random random, int index) {
        int kind = random.nextInt(20);
        if (kind == 0) {
            return new PolicyLayerInput.InvalidLayer(PolicyLayer.FILE, "broken-" + index, "invalid JSON");
        }
        if (kind <= 3) {
            Set<String> tools = random.nextBoolean() ? Set.of("calculate_discount") : Set.of("find_orders", "nope");
            Set<CatalogElementRef> refs = random.nextBoolean() ? Set.of(CatalogFixtures.ORDER) : Set.of(CatalogFixtures.TAX_ID);
            return new PolicyLayerInput.KillSwitchLayer("ks-" + index, random.nextInt(10) == 0, tools, refs, "incident");
        }
        Map<PolicyKey, PolicyOverride> overrides = new LinkedHashMap<>();
        int n = 1 + random.nextInt(5);
        for (int i = 0; i < n; i++) {
            overrides.put(KEYS.get(random.nextInt(KEYS.size())), randomOverride(random));
        }
        PolicyLayer layer = random.nextBoolean() ? PolicyLayer.FILE : PolicyLayer.OVERLAY;
        return new PolicyLayerInput.DocumentLayer(layer, "doc-" + index, new PolicyDocument(1, overrides));
    }

    private static PolicyOverride randomOverride(Random random) {
        Boolean enabled = pick(random, Boolean.TRUE, Boolean.FALSE);
        return new PolicyOverride(
                enabled,
                Boolean.FALSE.equals(enabled) ? "reason" : null,
                pick(random, "new description", null),
                random.nextBoolean() ? List.of("k" + random.nextInt(3)) : null,
                pick(random, Boolean.TRUE, Boolean.FALSE),
                random.nextBoolean() ? CLASSIFICATIONS[random.nextInt(CLASSIFICATIONS.length)] : null,
                random.nextBoolean() ? 1 + random.nextInt(300) : null,
                random.nextBoolean() ? List.of(random.nextBoolean() ? "tenantId" : "region") : null,
                pick(random, Boolean.TRUE, Boolean.FALSE),
                false);
    }

    private static <T> T pick(Random random, T a, T b) {
        int r = random.nextInt(3);
        return r == 0 ? a : r == 1 ? b : null;
    }
}
