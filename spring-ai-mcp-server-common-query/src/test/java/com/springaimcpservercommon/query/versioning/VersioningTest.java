package com.springaimcpservercommon.query.versioning;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.versioning.VersionLookup;
import com.springaimcpservercommon.core.versioning.VersionToken;
import com.springaimcpservercommon.core.versioning.VersioningAdapter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersioningTest {

    private static final CatalogElementRef ORDER = CatalogElementRef.entity("com.acme.Order");

    /** An entity manager factory that must never be used: the registry tests only exercise adapter ordering. */
    private static EntityManagerFactory unusedEmf() {
        return (EntityManagerFactory) Proxy.newProxyInstance(VersioningTest.class.getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static VersioningAdapter adapter(String id, boolean supports, VersionLookup answer, List<String> calls) {
        return new VersioningAdapter() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean supports(CatalogElementRef entity) {
                return supports;
            }

            @Override
            public VersionLookup lookup(CatalogElementRef entity, String entityId) {
                calls.add(id);
                if (answer == null) {
                    throw new IllegalStateException("adapter broken");
                }
                return answer;
            }
        };
    }

    private static VersioningRegistry registry(List<VersioningAdapter> adapters) {
        EffectiveCatalog catalog = new EffectiveCatalog(1, "sha256:" + "0".repeat(64), "sha256:" + "0".repeat(64),
                Map.of(), Map.of(), List.of(), List.of());
        return new VersioningRegistry(adapters, new JpaExposedValues(unusedEmf(), () -> catalog));
    }

    private static VersionLookup found(String value) {
        return new VersionLookup.Found(new VersionToken(VersionToken.Kind.CUSTOM, value));
    }

    @Test
    void theFirstAdapterThatSupportsTheEntityAnswers() {
        List<String> calls = new ArrayList<>();
        var registry = registry(List.of(adapter("skip", false, found("x"), calls), adapter("host", true, found("h"), calls),
                adapter("later", true, found("l"), calls)));

        assertThat(registry.current(ORDER, "1")).isEqualTo(found("h"));
        assertThat(calls).containsExactly("host");
    }

    @Test
    void aMissingRecordIsAnAnswerButAnUnreadableIdFallsThrough() {
        List<String> calls = new ArrayList<>();
        assertThat(registry(List.of(adapter("a", true, new VersionLookup.Missing(), calls),
                adapter("b", true, found("b"), calls))).current(ORDER, "1")).isInstanceOf(VersionLookup.Missing.class);

        calls.clear();
        assertThat(registry(List.of(adapter("a", true, new VersionLookup.Unsupported(), calls),
                adapter("b", true, found("b"), calls))).current(ORDER, "1")).isEqualTo(found("b"));
        assertThat(calls).containsExactly("a", "b");
    }

    @Test
    void aBrokenAdapterDegradesToTheNextOneAndNothingSupportingMeansUnsupported() {
        List<String> calls = new ArrayList<>();
        assertThat(registry(List.of(adapter("broken", true, null, calls), adapter("ok", true, found("ok"), calls)))
                .current(ORDER, "1")).isEqualTo(found("ok"));
        assertThat(registry(List.of(adapter("none", false, found("x"), calls))).current(ORDER, "1"))
                .isInstanceOf(VersionLookup.Unsupported.class);
        assertThat(registry(List.of()).exposedValues(ORDER, "1", Classification.INTERNAL)).isEmpty();
    }

    @Test
    void idsAreParsedToTheEntitysIdType() {
        assertThat(JpaRecords.parseId(Long.class, "42")).contains(42L);
        assertThat(JpaRecords.parseId(long.class, "42")).contains(42L);
        assertThat(JpaRecords.parseId(Integer.class, "7")).contains(7);
        assertThat(JpaRecords.parseId(String.class, "A-1")).contains("A-1");
        UUID uuid = UUID.randomUUID();
        assertThat(JpaRecords.parseId(UUID.class, uuid.toString())).contains(uuid);
        assertThat(JpaRecords.parseId(BigInteger.class, "12345678901234567890")).contains(
                new BigInteger("12345678901234567890"));
        assertThat(JpaRecords.parseId(Long.class, "abc")).isEmpty();
        assertThat(JpaRecords.parseId(UUID.class, "nope")).isEmpty();
        assertThat(JpaRecords.supportedIdType(Long.class)).isTrue();
        assertThat(JpaRecords.supportedIdType(java.time.Instant.class)).isFalse();
    }

    @Test
    void theRowHashIsStableIndependentOfInsertionOrderAndSensitiveToValues() {
        List<String> names = List.of("status", "total");
        String a = RowHashAdapter.hash(names, new Object[] {"PAID", new BigDecimal("120.50")});
        String same = RowHashAdapter.hash(names, new Object[] {"PAID", new BigDecimal("120.5")});
        String other = RowHashAdapter.hash(names, new Object[] {"SHIPPED", new BigDecimal("120.50")});

        assertThat(a).startsWith("sha256:").isEqualTo(same).isNotEqualTo(other);
        assertThat(new VersionToken(VersionToken.Kind.ROW_HASH, a).asReference()).startsWith("ROW_HASH:sha256:");
    }

    @Test
    void valuesAreNormalisedForTheCanonicalWriter() {
        assertThat(RowHashAdapter.normalise(null)).isNull();
        assertThat(RowHashAdapter.normalise("x")).isEqualTo("x");
        assertThat(RowHashAdapter.normalise(3)).isEqualTo(3);
        assertThat(RowHashAdapter.normalise(2.5d)).isEqualTo("2.5");
        assertThat(RowHashAdapter.normalise(Thread.State.NEW)).isEqualTo("NEW");
        assertThat(RowHashAdapter.normalise(LocalDate.of(2026, 9, 29))).isEqualTo("2026-09-29");
        assertThat(RowHashAdapter.normalise(new byte[] {1, 2, 3})).isEqualTo("AQID");
        // the hash never fails on a type the canonical writer does not know
        assertThat(RowHashAdapter.hash(List.of("d"), new Object[] {LocalDate.of(2026, 9, 29)})).startsWith("sha256:");
    }

    @Test
    void versionTokensAreValidated() {
        assertThatThrownBy(() -> new VersionToken(VersionToken.Kind.CUSTOM, " ")).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> new VersionToken(VersionToken.Kind.CUSTOM, "x".repeat(513))).isInstanceOf(
                IllegalArgumentException.class);
        assertThat(new VersionToken(VersionToken.Kind.JPA_VERSION, "7").asReference()).isEqualTo("JPA_VERSION:7");
    }
}
