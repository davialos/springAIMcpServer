package com.springaimcpservercommon.core.versioning;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

/**
 * SPI: reads the current version of a host record with one versioning mechanism. Built-in adapters cover JPA
 * {@code @Version} and a row-hash fallback; a host with Envers, a history table or a temporal table registers its own
 * bean and is consulted first.
 *
 * <p>Implementations only <em>read</em>: they must not write, must not throw for a missing record (answer
 * {@link VersionLookup.Missing}), and should use a read-only access to the host data. They never see a caller's
 * identity: the version marker is not data, but implementations must not put attribute values into it that could be read
 * back (a hash of low-entropy values can be guessed), so sensitive attributes stay out of it.
 */
public interface VersioningAdapter {

    /** @return stable adapter id for logs and metrics (for example {@code jpa-version}) */
    String id();

    /**
     * Whether this adapter versions the entity.
     *
     * @param entity catalog reference of the entity
     * @return {@code true} if {@link #lookup} can answer for it
     */
    boolean supports(CatalogElementRef entity);

    /**
     * Reads the current version of one record.
     *
     * @param entity   catalog reference of the entity
     * @param entityId the record's id in its text form (converted to the entity's id type by the adapter)
     * @return the current version, {@link VersionLookup.Missing} for an unknown record, or
     *         {@link VersionLookup.Unsupported} when the id cannot be read
     */
    VersionLookup lookup(CatalogElementRef entity, String entityId);
}
