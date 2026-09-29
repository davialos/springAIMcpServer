package com.springaimcpservercommon.core.versioning;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;

import java.util.Map;
import java.util.Optional;

/**
 * What the write path asks about host records (LLD-11 §5): the current version, and optionally the exposed attribute
 * values for the review diff. Implemented over the registered {@link VersioningAdapter}s.
 */
public interface RecordVersions {

    /**
     * Current version of a record, from the first adapter that supports the entity.
     *
     * @param entity   catalog reference of the entity
     * @param entityId the record's id in text form
     * @return the lookup result
     */
    VersionLookup current(CatalogElementRef entity, String entityId);

    /**
     * The record's exposed attribute values as they may be shown to a caller: only attributes that are enabled in the
     * catalog, not sensitive, and classified at or below {@code clearance}. Row-level visibility of the host is
     * <em>not</em> applied here, which is why callers make this opt-in.
     *
     * @param entity    catalog reference of the entity
     * @param entityId  the record's id in text form
     * @param clearance highest classification the caller may see
     * @return attribute name to value, empty when the record or the entity cannot be read
     */
    Optional<Map<String, Object>> exposedValues(CatalogElementRef entity, String entityId, Classification clearance);
}
