package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.json.CanonicalJson;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed and validated policy document (schema {@code https://dynamic-ai/schemas/policy/1.json}, LLD-03 §4.2).
 * Override order is the document order; keys are unique.
 *
 * @param schemaVersion always {@value #SCHEMA_VERSION}
 * @param overrides     overrides by key, in document order
 */
public record PolicyDocument(int schemaVersion, Map<PolicyKey, PolicyOverride> overrides) {

    /** The only supported schema version. */
    public static final int SCHEMA_VERSION = 1;

    /** Validates components and copies the map (order preserved). */
    public PolicyDocument {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported policy schemaVersion " + schemaVersion);
        }
        overrides = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(overrides, "overrides")));
    }

    /**
     * An empty document (e.g. an optional file location that does not exist).
     *
     * @return empty document
     */
    public static PolicyDocument empty() {
        return new PolicyDocument(SCHEMA_VERSION, Map.of());
    }

    /**
     * Canonical JSON of the document (keys sorted), for fingerprints and audit.
     *
     * @return canonical JSON
     */
    public String canonicalJson() {
        Map<String, Object> o = new LinkedHashMap<>();
        overrides.forEach((k, v) -> o.put(k.text(), v.canonical()));
        return CanonicalJson.write(Map.of("schemaVersion", schemaVersion, "overrides", o));
    }

    /**
     * {@code sha256:} of {@link #canonicalJson()}.
     *
     * @return the fingerprint
     */
    public String fingerprint() {
        return Sha256.of(canonicalJson());
    }
}
