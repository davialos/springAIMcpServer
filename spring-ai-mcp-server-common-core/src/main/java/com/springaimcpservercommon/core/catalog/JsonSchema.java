package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable JSON Schema (draft 2020-12 vocabulary) document.
 *
 * <p>Representation choice: the schema is held both as a deeply immutable value tree ({@link #tree()}, made of
 * {@code Map<String, Object>}, {@code List<Object>}, {@code String}, {@code Boolean} and numbers) and as its
 * <em>canonical</em> JSON rendering ({@link #json()}, sorted keys, no whitespace). Equality and hashing use the
 * canonical text, so two schemas are equal exactly when their content is. The core deliberately does not expose
 * a Jackson node: consumers (tool bridge, MCP, admin API) parse {@link #json()} with their own mapper.
 */
public final class JsonSchema {

    private static final JsonSchema EMPTY_OBJECT = of(Map.of("type", "object", "properties", Map.of()));

    private final Map<String, Object> tree;
    private final String json;

    private JsonSchema(Map<String, Object> tree, String json) {
        this.tree = tree;
        this.json = json;
    }

    /**
     * Creates a schema from a value tree; the tree is deep-copied.
     *
     * @param tree schema as maps, lists and scalars
     * @return the schema
     * @throws IllegalArgumentException if the tree contains unsupported values
     */
    @SuppressWarnings("unchecked")
    public static JsonSchema of(Map<String, ?> tree) {
        Objects.requireNonNull(tree, "tree");
        Map<String, Object> copy = (Map<String, Object>) Objects.requireNonNull(CanonicalJson.immutableCopy(tree));
        return new JsonSchema(copy, CanonicalJson.write(copy));
    }

    /**
     * The schema of an object without properties ({@code {"properties":{},"type":"object"}}), used for
     * parameterless actions.
     *
     * @return the empty object schema
     */
    public static JsonSchema emptyObject() {
        return EMPTY_OBJECT;
    }

    /**
     * Deeply immutable value tree of the schema.
     *
     * @return the tree
     */
    public Map<String, Object> tree() {
        return tree;
    }

    /**
     * Canonical JSON text (sorted keys, no whitespace).
     *
     * @return the JSON text
     */
    public String json() {
        return json;
    }

    /**
     * SHA-256 of the canonical text ({@code sha256:<hex>}), used for drift detection.
     *
     * @return the hash
     */
    public String hash() {
        return Sha256.of(json);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof JsonSchema other && json.equals(other.json));
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }

    @Override
    public String toString() {
        return json;
    }
}
