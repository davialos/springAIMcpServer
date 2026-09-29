package com.springaimcpservercommon.query.versioning;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.core.versioning.VersionLookup;
import com.springaimcpservercommon.core.versioning.VersionToken;
import com.springaimcpservercommon.core.versioning.VersioningAdapter;
import jakarta.persistence.EntityManagerFactory;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The fallback for entities without {@code @Version}: the version is a SHA-256 over the record's exposed, enabled,
 * <em>non-sensitive</em> basic attributes (LLD-11 §5 "RowHashAdapter"). It detects a change of any of those attributes;
 * it cannot see changes to hidden or sensitive ones (sensitive values are left out on purpose, because a hash of a
 * low-entropy value could be guessed back by whoever holds the token). Hosts that need stronger guarantees register a
 * {@link VersioningAdapter} of their own.
 */
@NullMarked
public final class RowHashAdapter implements VersioningAdapter {

    private final JpaRecords records;
    private final Supplier<EffectiveCatalog> catalog;

    /**
     * Creates the adapter.
     *
     * @param entityManagerFactory the host's entity manager factory
     * @param catalog              the live catalog (which attributes are exposed)
     */
    public RowHashAdapter(EntityManagerFactory entityManagerFactory, Supplier<EffectiveCatalog> catalog) {
        this.records = new JpaRecords(Objects.requireNonNull(entityManagerFactory, "entityManagerFactory"));
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public String id() {
        return "row-hash";
    }

    @Override
    public boolean supports(CatalogElementRef entity) {
        JpaRecords.Meta meta = records.meta(entity).orElse(null);
        return meta != null && !hashedAttributes(meta, entity).isEmpty();
    }

    @Override
    public VersionLookup lookup(CatalogElementRef entity, String entityId) {
        JpaRecords.Meta meta = records.meta(entity).orElse(null);
        if (meta == null) {
            return new VersionLookup.Unsupported();
        }
        List<String> attributes = hashedAttributes(meta, entity);
        if (attributes.isEmpty()) {
            return new VersionLookup.Unsupported();
        }
        return switch (records.read(meta, entityId, attributes)) {
            case JpaRecords.Row.Found found -> new VersionLookup.Found(new VersionToken(VersionToken.Kind.ROW_HASH,
                    hash(attributes, found.values())));
            case JpaRecords.Row.Missing missing -> new VersionLookup.Missing();
            case JpaRecords.Row.Unsupported unsupported -> new VersionLookup.Unsupported();
        };
    }

    /** Names of the attributes the hash covers: exposed, enabled, not sensitive, basic; sorted for a stable order. */
    private List<String> hashedAttributes(JpaRecords.Meta meta, CatalogElementRef entity) {
        EffectiveEntity effective = catalog.get().entity(entity).orElse(null);
        if (effective == null || !effective.enabled()) {
            return List.of();
        }
        return effective.attributes().values().stream()
                .filter(EffectiveAttribute::enabled)
                .filter(a -> !a.sensitive() && !a.descriptor().sensitive())
                .map(a -> a.descriptor().name())
                .filter(name -> JpaRecords.isBasic(meta, name))
                .sorted()
                .toList();
    }

    /** The hash of attribute names and normalised values; package-private so it is testable without a database. */
    static String hash(List<String> attributes, Object[] values) {
        Map<String, Object> ordered = new TreeMap<>();
        for (int i = 0; i < attributes.size(); i++) {
            ordered.put(attributes.get(i), normalise(values[i]));
        }
        return Sha256.of(CanonicalJson.write(ordered));
    }

    /** Turns a JPA value into something the canonical JSON writer renders; other types by their text form. */
    static @Nullable Object normalise(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Number n when n instanceof BigDecimal || n instanceof BigInteger || n instanceof Long
                    || n instanceof Integer || n instanceof Short || n instanceof Byte -> n;
            case Number n -> n.toString();
            case UUID u -> u;
            case Enum<?> e -> e.name();
            case byte[] bytes -> Base64.getEncoder().encodeToString(bytes);
            default -> value.toString();
        };
    }
}
