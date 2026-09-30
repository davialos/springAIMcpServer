/**
 * Built-in {@link com.springaimcpservercommon.core.versioning.VersioningAdapter}s over the host's JPA metamodel: the
 * entity's {@code @Version} attribute and a row-hash fallback, and the {@code VersioningRegistry} that consults host
 * adapters first (LLD-11 §5). Read-only; plain JPQL, no reflection, no Hibernate-specific API.
 */
@NullMarked
package com.springaimcpservercommon.query.versioning;

import org.jspecify.annotations.NullMarked;
