package com.springaimcpservercommon.query.versioning;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.versioning.VersionLookup;
import com.springaimcpservercommon.core.versioning.VersionToken;
import com.springaimcpservercommon.core.versioning.VersioningAdapter;
import jakarta.persistence.EntityManagerFactory;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Objects;

/**
 * Versions a record by its entity's JPA {@code @Version} attribute (LLD-11 §5). The host's own optimistic lock is what
 * increments it, so "the version is unchanged" means "nothing wrote the row since": the same guard the host applies at
 * flush. Read with a one-attribute JPQL projection; nothing is loaded or written.
 */
@NullMarked
public final class JpaVersionAdapter implements VersioningAdapter {

    private final JpaRecords records;

    /**
     * Creates the adapter.
     *
     * @param entityManagerFactory the host's entity manager factory
     */
    public JpaVersionAdapter(EntityManagerFactory entityManagerFactory) {
        this.records = new JpaRecords(Objects.requireNonNull(entityManagerFactory, "entityManagerFactory"));
    }

    @Override
    public String id() {
        return "jpa-version";
    }

    @Override
    public boolean supports(CatalogElementRef entity) {
        return records.meta(entity).map(m -> m.versionName() != null).orElse(false);
    }

    @Override
    public VersionLookup lookup(CatalogElementRef entity, String entityId) {
        JpaRecords.Meta meta = records.meta(entity).orElse(null);
        if (meta == null || meta.versionName() == null) {
            return new VersionLookup.Unsupported();
        }
        return switch (records.read(meta, entityId, List.of(meta.versionName()))) {
            case JpaRecords.Row.Found found -> found.values()[0] == null ? new VersionLookup.Unsupported()
                    : new VersionLookup.Found(new VersionToken(VersionToken.Kind.JPA_VERSION,
                    String.valueOf(found.values()[0])));
            case JpaRecords.Row.Missing missing -> new VersionLookup.Missing();
            case JpaRecords.Row.Unsupported unsupported -> new VersionLookup.Unsupported();
        };
    }
}
