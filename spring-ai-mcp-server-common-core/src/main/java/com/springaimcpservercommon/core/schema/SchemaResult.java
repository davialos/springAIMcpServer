package com.springaimcpservercommon.core.schema;

import com.springaimcpservercommon.core.catalog.JsonSchema;

import java.util.List;
import java.util.Objects;

/**
 * A mapped schema plus the facts the scanner needs for lint (LLD-14 §3.2, LLD-02 §4).
 *
 * @param schema                   the JSON schema
 * @param maxNesting               deepest object/array nesting below a top-level value (a flat scalar is 0,
 *                                 a record of scalars 1)
 * @param containsMap              a {@code Map} occurs somewhere
 * @param polymorphic              an interface, abstract class, {@code Object} or unknown JDK type occurs
 * @param depthLimitReached        mapping stopped at the depth limit somewhere
 * @param removedSensitiveMembers  member paths removed because they are {@code sensitive=true} or ignored
 * @param unconfirmedSensitiveNames member paths removed by the sensitive-name heuristic (issue
 *                                 {@code SENSITIVE_NAME_UNCONFIRMED})
 */
public record SchemaResult(JsonSchema schema, int maxNesting, boolean containsMap, boolean polymorphic,
                           boolean depthLimitReached, List<String> removedSensitiveMembers,
                           List<String> unconfirmedSensitiveNames) {

    /** Validates components and copies collections. */
    public SchemaResult {
        Objects.requireNonNull(schema, "schema");
        removedSensitiveMembers = List.copyOf(removedSensitiveMembers);
        unconfirmedSensitiveNames = List.copyOf(unconfirmedSensitiveNames);
    }

    /**
     * Whether the arguments are complex per LLD-14 §3.2 (nesting &gt; 2, maps, polymorphic types, depth limit).
     *
     * @return {@code true} if the {@code COMPLEX_TOOL_ARGS} warning applies
     */
    public boolean complex() {
        return maxNesting > 2 || containsMap || polymorphic || depthLimitReached;
    }
}
