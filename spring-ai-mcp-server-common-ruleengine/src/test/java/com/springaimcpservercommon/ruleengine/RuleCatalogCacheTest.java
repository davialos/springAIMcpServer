package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.cache.RuleCatalogCache;
import com.springaimcpservercommon.ruleengine.cache.RuleCatalogUnavailableException;
import com.springaimcpservercommon.ruleengine.cache.TenantCatalog;
import com.springaimcpservercommon.ruleengine.model.Parameter;
import com.springaimcpservercommon.ruleengine.store.Scope;
import com.springaimcpservercommon.ruleengine.store.TenantData;
import com.springaimcpservercommon.ruleengine.support.InMemoryRuleStore;
import com.springaimcpservercommon.ruleengine.support.SampleTenant;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleCatalogCacheTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final InMemoryRuleStore store = new InMemoryRuleStore();
    private final TestClock clock = new TestClock();
    private final RuleCatalogCache cache = new RuleCatalogCache(store, clock, Duration.ofSeconds(10), 2, "en");

    @Test
    void aTenantIsLoadedOnceAndServedFromMemoryWithinThePollInterval() {
        TenantCatalog first = cache.catalog(SampleTenant.TENANT);
        clock.advance(Duration.ofSeconds(5));

        assertThat(cache.catalog(SampleTenant.TENANT)).isSameAs(first);
        assertThat(store.tenantLoads).hasValue(1);
    }

    @Test
    void anUnchangedMarkerKeepsTheSnapshotAfterThePollInterval() {
        TenantCatalog first = cache.catalog(SampleTenant.TENANT);
        clock.advance(Duration.ofSeconds(11));

        assertThat(cache.catalog(SampleTenant.TENANT)).isSameAs(first);
        assertThat(store.tenantLoads).as("only the markers were read").hasValue(1);
    }

    @Test
    void aMovedRulesMarkerReloadsTheTenantAtTheNextPoll() {
        TenantCatalog first = cache.catalog(SampleTenant.TENANT);
        store.tenant = new TenantData(SampleTenant.TENANT, List.of(), List.of(), List.of(), Map.of(), Map.of());
        store.bump(Scope.RULES);

        assertThat(cache.catalog(SampleTenant.TENANT)).as("not yet polled").isSameAs(first);
        clock.advance(Duration.ofSeconds(11));

        TenantCatalog second = cache.catalog(SampleTenant.TENANT);
        assertThat(second).isNotSameAs(first);
        assertThat(second.data().groups()).isEmpty();
    }

    @Test
    void aParameterChangeRecompilesRulesAgainstTheNewLibrary() {
        TenantCatalog first = cache.catalog(SampleTenant.TENANT);
        List<Parameter> without = new ArrayList<>(store.parameters);
        without.removeIf(p -> p.celName().equals("customer.age"));
        store.parameters = without;
        store.bump(Scope.PARAMETERS);
        clock.advance(Duration.ofSeconds(11));

        TenantCatalog second = cache.catalog(SampleTenant.TENANT);
        assertThat(second.library().find("customer.age")).isEmpty();
        assertThat(first.library().find("customer.age")).as("old snapshot is untouched").isPresent();
        var adult = second.data().groups().getFirst().rules().getFirst().rule();
        assertThat(second.compiled(adult).compileError()).contains("customer.age");
    }

    @Test
    void whenTheStoreIsDownTheLastSnapshotKeepsServing() {
        TenantCatalog first = cache.catalog(SampleTenant.TENANT);
        store.failing = true;
        clock.advance(Duration.ofSeconds(11));

        assertThat(cache.catalog(SampleTenant.TENANT)).isSameAs(first);
    }

    @Test
    void aTenantThatNeverLoadedFailsTheFeatureNotTheHost() {
        store.failing = true;
        assertThatThrownBy(() -> cache.catalog(SampleTenant.TENANT))
                .isInstanceOf(RuleCatalogUnavailableException.class);
    }

    @Test
    void theNumberOfCachedTenantsIsBounded() {
        cache.catalog(UUID.randomUUID());
        clock.advance(Duration.ofSeconds(1));
        cache.catalog(UUID.randomUUID());
        clock.advance(Duration.ofSeconds(1));
        cache.catalog(UUID.randomUUID());

        assertThat(store.tenantLoads).hasValue(3);
        // the oldest tenant was dropped, so loading it again costs a load
        cache.invalidate();
        cache.catalog(SampleTenant.TENANT);
        assertThat(store.tenantLoads).hasValue(4);
    }
}
