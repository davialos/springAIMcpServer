package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.PolicyLayer;
import com.springaimcpservercommon.core.lint.TextLint;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict parser for policy JSON (schema version 1, LLD-03 §4.2), built on a private Jackson 3 {@link JsonMapper}
 * (never a host bean, ADR-0019).
 *
 * <p>Strictness: duplicate keys, trailing tokens, unknown fields, {@code null} values and wrong types are errors;
 * {@code reason} is mandatory with {@code enabled=false}; {@code declassify} is only accepted in OVERLAY documents;
 * texts are linted (length, secrets). All errors are collected and reported together.
 */
public final class PolicyDocumentParser {

    private static final Set<String> ROOT_FIELDS = Set.of("$schema", "schemaVersion", "overrides");
    private static final Set<String> OVERRIDE_FIELDS = Set.of("enabled", "reason", "descriptionOverride", "keywords",
            "sensitive", "classification", "maxLimit", "mandatoryFilters", "readOnly", "declassify");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");
    private static final int MAX_LIMIT_CEILING = 100_000;
    private static final int MAX_OVERRIDES = 10_000;

    private final JsonMapper mapper;
    private final TextLint lint;

    /** Creates a parser with the default text lint. */
    public PolicyDocumentParser() {
        this(TextLint.defaults());
    }

    /**
     * Creates a parser.
     *
     * @param lint text lint for descriptions, reasons and keywords
     */
    public PolicyDocumentParser(TextLint lint) {
        this.lint = Objects.requireNonNull(lint, "lint");
        this.mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .build();
    }

    /**
     * Parses and validates a document.
     *
     * @param json  document text
     * @param layer {@link PolicyLayer#FILE} or {@link PolicyLayer#OVERLAY}
     * @return the document
     * @throws PolicyValidationException with all errors if the text is invalid
     */
    public PolicyDocument parse(String json, PolicyLayer layer) {
        Objects.requireNonNull(json, "json");
        if (layer != PolicyLayer.FILE && layer != PolicyLayer.OVERLAY) {
            throw new IllegalArgumentException("only FILE and OVERLAY layers are documents, not " + layer);
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            // the parser message may quote document content, so only the exception type is reported
            throw new PolicyValidationException(List.of("$: not valid JSON, or duplicate keys / trailing content ("
                    + e.getClass().getSimpleName() + ")"));
        }
        List<String> errors = new ArrayList<>();
        if (root == null || root.isMissingNode() || !root.isObject()) {
            throw new PolicyValidationException(List.of("$: document must be a JSON object"));
        }
        checkFields(root, ROOT_FIELDS, "$", errors);
        JsonNode schema = root.get("$schema");
        if (schema != null && !schema.isString()) {
            errors.add("$.$schema: must be a string");
        }
        JsonNode version = root.get("schemaVersion");
        if (version == null) {
            errors.add("$.schemaVersion: is required");
        } else if (!version.isInt() || version.intValue() != PolicyDocument.SCHEMA_VERSION) {
            errors.add("$.schemaVersion: must be " + PolicyDocument.SCHEMA_VERSION);
        }
        Map<PolicyKey, PolicyOverride> overrides = new LinkedHashMap<>();
        JsonNode overridesNode = root.get("overrides");
        if (overridesNode == null) {
            errors.add("$.overrides: is required");
        } else if (!overridesNode.isObject()) {
            errors.add("$.overrides: must be an object");
        } else if (overridesNode.size() > MAX_OVERRIDES) {
            errors.add("$.overrides: more than " + MAX_OVERRIDES + " entries");
        } else {
            for (Map.Entry<String, JsonNode> entry : overridesNode.properties()) {
                String path = "$.overrides[\"" + safeKey(entry.getKey()) + "\"]";
                PolicyKey key;
                try {
                    key = PolicyKey.parse(entry.getKey());
                } catch (IllegalArgumentException e) {
                    errors.add(path + ": invalid key (" + e.getMessage() + ")");
                    continue;
                }
                PolicyOverride override = parseOverride(entry.getValue(), key, layer, path, errors);
                if (override != null) {
                    overrides.put(key, override);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new PolicyValidationException(errors);
        }
        return new PolicyDocument(PolicyDocument.SCHEMA_VERSION, overrides);
    }

    /**
     * Parses a layer without throwing: invalid content becomes a fail-closed
     * {@link PolicyLayerInput.InvalidLayer}.
     *
     * @param layer    {@link PolicyLayer#FILE} or {@link PolicyLayer#OVERLAY}
     * @param sourceId source id recorded in provenance
     * @param json     document text
     * @return a document layer or an invalid layer
     */
    public PolicyLayerInput parseLayer(PolicyLayer layer, String sourceId, String json) {
        try {
            return new PolicyLayerInput.DocumentLayer(layer, sourceId, parse(json, layer));
        } catch (PolicyValidationException e) {
            return new PolicyLayerInput.InvalidLayer(layer, sourceId, String.join("; ", e.errors()));
        }
    }

    private @Nullable PolicyOverride parseOverride(JsonNode node, PolicyKey key, PolicyLayer layer, String path,
                                                   List<String> errors) {
        if (!node.isObject()) {
            errors.add(path + ": must be an object");
            return null;
        }
        int before = errors.size();
        checkFields(node, OVERRIDE_FIELDS, path, errors);
        Boolean enabled = bool(node, "enabled", path, errors);
        String reason = text(node, "reason", path, TextLint.MAX_DESCRIPTION_LENGTH, errors);
        boolean attribute = key instanceof PolicyKey.Canonical c && c.ref().kind() == CatalogElementRef.Kind.ATTR;
        String description = text(node, "descriptionOverride", path,
                attribute ? TextLint.MAX_MEANING_LENGTH : TextLint.MAX_DESCRIPTION_LENGTH, errors);
        List<String> keywords = strings(node, "keywords", path, errors, false);
        if (keywords != null) {
            lint.checkKeywords(keywords).forEach(f -> errors.add(path + ".keywords: " + f.detail()));
        }
        Boolean sensitive = bool(node, "sensitive", path, errors);
        Classification classification = classification(node, path, errors);
        Integer maxLimit = null;
        JsonNode limitNode = node.get("maxLimit");
        if (limitNode != null) {
            if (!limitNode.isInt() || limitNode.intValue() < 1 || limitNode.intValue() > MAX_LIMIT_CEILING) {
                errors.add(path + ".maxLimit: must be an integer between 1 and " + MAX_LIMIT_CEILING);
            } else {
                maxLimit = limitNode.intValue();
            }
        }
        List<String> filters = strings(node, "mandatoryFilters", path, errors, true);
        Boolean readOnly = bool(node, "readOnly", path, errors);
        Boolean declassify = bool(node, "declassify", path, errors);
        if (Boolean.FALSE.equals(enabled) && reason == null) {
            errors.add(path + ".reason: is mandatory when enabled is false");
        }
        if (Boolean.TRUE.equals(declassify)) {
            if (layer != PolicyLayer.OVERLAY) {
                errors.add(path + ".declassify: only allowed in OVERLAY (dashboard) layers");
            } else if (classification == null && !Boolean.FALSE.equals(sensitive)) {
                errors.add(path + ".declassify: requires classification or sensitive=false");
            }
        }
        if (errors.size() > before) {
            return null;
        }
        return new PolicyOverride(enabled, reason, description, keywords, sensitive, classification, maxLimit,
                filters, readOnly, Boolean.TRUE.equals(declassify));
    }

    private static void checkFields(JsonNode node, Set<String> allowed, String path, List<String> errors) {
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!allowed.contains(e.getKey())) {
                errors.add(path + ": unknown field \"" + safeKey(e.getKey()) + "\"");
            } else if (e.getValue().isNull()) {
                errors.add(path + "." + e.getKey() + ": null is not allowed; omit the field instead");
            }
        }
    }

    private static @Nullable Boolean bool(JsonNode node, String field, String path, List<String> errors) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isBoolean()) {
            errors.add(path + "." + field + ": must be a boolean");
            return null;
        }
        return v.booleanValue();
    }

    private @Nullable String text(JsonNode node, String field, String path, int maxLength, List<String> errors) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isString()) {
            errors.add(path + "." + field + ": must be a string");
            return null;
        }
        String s = v.stringValue();
        List<TextLint.Finding> findings = lint.check(s, maxLength);
        findings.forEach(f -> errors.add(path + "." + field + ": " + f.detail()));
        return findings.isEmpty() ? s : null;
    }

    private static @Nullable List<String> strings(JsonNode node, String field, String path, List<String> errors,
                                                  boolean identifiers) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isArray()) {
            errors.add(path + "." + field + ": must be an array of strings");
            return null;
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        for (JsonNode element : v) {
            if (!element.isString() || element.stringValue().isBlank()) {
                errors.add(path + "." + field + "[" + i + "]: must be a non-blank string");
            } else if (identifiers && !IDENTIFIER.matcher(element.stringValue()).matches()) {
                errors.add(path + "." + field + "[" + i + "]: must be an attribute name or dotted path");
            } else {
                out.add(element.stringValue());
            }
            i++;
        }
        return out;
    }

    private static @Nullable Classification classification(JsonNode node, String path, List<String> errors) {
        JsonNode v = node.get("classification");
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isString()) {
            try {
                Classification c = Classification.valueOf(v.stringValue().toUpperCase(Locale.ROOT));
                if (c != Classification.INHERIT) {
                    return c;
                }
            } catch (IllegalArgumentException ignored) {
                // reported below
            }
        }
        errors.add(path + ".classification: must be one of PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED");
        return null;
    }

    private static String safeKey(String key) {
        String k = key.length() > 200 ? key.substring(0, 200) + "…" : key;
        return k.replaceAll("[\\p{Cntrl}\"\\\\]", "?");
    }
}
