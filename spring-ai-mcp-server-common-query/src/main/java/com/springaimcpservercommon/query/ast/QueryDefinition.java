package com.springaimcpservercommon.query.ast;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable, published dynamic query definition (LLD-05 §2).
 *
 * <p>Query definitions are authored in the admin dashboard, validated at publish time against the
 * effective catalog (LLD-05 §3), stored as JSON in {@code dai_query_revision}, and compiled to
 * JPA Criteria queries at runtime by {@link com.springaimcpservercommon.query.criteria.CriteriaCompiler}.
 *
 * <p>The compiled plan is cached by {@code (id, revision, policyFingerprint)} — a new generation of
 * the effective catalog or a change to applicable row policies invalidates the cache.
 *
 * @param id           unique query id (UUIDv7)
 * @param revision     monotonically increasing revision; incremented on every publish
 * @param workspaceId  owning workspace
 * @param root         root entity for the FROM clause (kind must be {@code ENTITY})
 * @param select       columns to return; must be non-empty
 * @param where        optional WHERE filter tree
 * @param orderBy      sort columns; at least one is required when keyset pagination is enabled
 * @param page         pagination defaults and hard caps
 * @param params       caller-supplied parameters declared for this query
 * @param references   all catalog elements this query references (for drift detection — LLD-03)
 * @param catalogHash  {@code sha256:} fingerprint of the effective catalog generation validated against
 */
public record QueryDefinition(
        UUID id,
        int revision,
        UUID workspaceId,
        CatalogElementRef root,
        List<Projection> select,
        @Nullable FilterNode where,
        List<SortSpec> orderBy,
        PageSpec page,
        List<QueryParam> params,
        Set<CatalogElementRef> references,
        String catalogHash) {

    /** Validates components and defensively copies collections. */
    public QueryDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(root, "root");
        if (root.kind() != CatalogElementRef.Kind.ENTITY) {
            throw new IllegalArgumentException("root must be an ENTITY ref: " + root);
        }
        Objects.requireNonNull(select, "select");
        if (select.isEmpty()) {
            throw new IllegalArgumentException("select must have at least one projection");
        }
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(catalogHash, "catalogHash");
        select = List.copyOf(select);
        orderBy = List.copyOf(orderBy);
        params = List.copyOf(params);
        references = Set.copyOf(references);
    }

    /**
     * Finds a declared parameter by name.
     *
     * @param name parameter name
     * @return the parameter, or {@code null} if not declared
     */
    public @Nullable QueryParam param(String name) {
        for (QueryParam p : params) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }
}
