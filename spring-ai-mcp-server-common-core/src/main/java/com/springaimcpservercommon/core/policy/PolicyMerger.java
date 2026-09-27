package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.AttributeDescriptor;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.catalog.EntityDescriptor;
import com.springaimcpservercommon.core.catalog.OperationDescriptor;
import com.springaimcpservercommon.core.catalog.PolicyLayer;
import com.springaimcpservercommon.core.catalog.PolicyLayerStatus;
import com.springaimcpservercommon.core.catalog.PolicyProvenance;
import com.springaimcpservercommon.core.catalog.ScanIssue;
import com.springaimcpservercommon.core.catalog.ScanIssueCode;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Merges a {@link ScannedCatalog} (layer L0, code) with ordered policy layers into an {@link EffectiveCatalog},
 * implementing exactly the rules of LLD-03 §4.1:
 * <ul>
 *   <li><b>Exposure</b>: only code exposes; layers can reference only scanned elements (unknown refs →
 *       {@link ScanIssueCode#POLICY_REF_UNKNOWN}, a warning, an error when {@code strict}).</li>
 *   <li><b>enabled</b>: logical AND — any layer can disable, none can re-enable.</li>
 *   <li><b>readOnly</b>: can only tighten; {@code readOnly: true} on a write action disables it,
 *       {@code readOnly: false} is rejected ({@link ScanIssueCode#POLICY_READ_ONLY_LOOSENING}).</li>
 *   <li><b>description / meaning / keywords</b>: the latest layer with a non-empty value wins.</li>
 *   <li><b>sensitive, classification</b>: maximum across layers; lowering only in an OVERLAY override that sets
 *       {@code declassify} (else {@link ScanIssueCode#POLICY_DECLASSIFY_REJECTED}).</li>
 *   <li><b>maxLimit</b>: minimum across code, layers and the global cap. <b>mandatoryFilters</b>: union.</li>
 *   <li><b>Tool name</b>: code only (the policy schema has no field for it).</li>
 *   <li><b>Shorthand</b> {@code Class.method}: must match exactly one operation; an overloaded shorthand is an
 *       error naming the canonical refs, and the whole layer is treated as invalid.</li>
 *   <li><b>Fail closed</b> (LLD-03 §4.3): an invalid FILE/OVERLAY layer disables every operation and entity,
 *       with provenance explaining why.</li>
 *   <li><b>Kill switches</b>: disable by tool name, by element (an entity also disables operations returning it)
 *       or globally.</li>
 * </ul>
 * Layers are applied in {@link PolicyLayer} order (stable for several inputs of the same layer). The merger is
 * stateless and thread-safe.
 */
public final class PolicyMerger {

    /** Default global row cap when none is configured. */
    public static final int DEFAULT_GLOBAL_MAX_LIMIT = 500;

    private final int globalMaxLimit;
    private final boolean strict;

    /**
     * Creates a merger.
     *
     * @param globalMaxLimit global row cap (≥ 1) applied to entities and list operations
     * @param strict         {@code true} raises unknown policy refs to errors ({@code scan.strict})
     */
    public PolicyMerger(int globalMaxLimit, boolean strict) {
        if (globalMaxLimit < 1) {
            throw new IllegalArgumentException("globalMaxLimit must be >= 1");
        }
        this.globalMaxLimit = globalMaxLimit;
        this.strict = strict;
    }

    /**
     * Builds the effective catalog.
     *
     * @param scanned    the scanned catalog (code layer)
     * @param layers     policy layer inputs (FILE, OVERLAY, KILL_SWITCH; any order, applied in layer order)
     * @param generation generation number of the result
     * @return the effective catalog
     */
    public EffectiveCatalog merge(ScannedCatalog scanned, List<PolicyLayerInput> layers, long generation) {
        Objects.requireNonNull(scanned, "scanned");
        Objects.requireNonNull(layers, "layers");
        Merge m = new Merge(scanned);
        List<PolicyLayerInput> ordered = layers.stream()
                .sorted(Comparator.comparing(PolicyLayerInput::layer)).toList();
        for (PolicyLayerInput input : ordered) {
            switch (input) {
                case PolicyLayerInput.DocumentLayer doc -> m.applyDocument(doc);
                case PolicyLayerInput.InvalidLayer invalid -> m.failClosed(invalid.layer(), invalid.sourceId(),
                        invalid.error());
                case PolicyLayerInput.KillSwitchLayer kill -> m.applyKillSwitch(kill);
            }
        }
        return m.result(generation);
    }

    // ---------------------------------------------------------------------------------------------------------

    private static final class EntityState {
        final EntityDescriptor d;
        String description;
        List<String> keywords;
        Classification classification;
        int maxLimit;
        final TreeSet<String> filters;
        boolean enabled = true;
        final List<PolicyProvenance> provenance = new ArrayList<>();
        final Map<CatalogElementRef, AttrState> attributes = new LinkedHashMap<>();

        EntityState(EntityDescriptor d) {
            this.d = d;
            this.description = d.description();
            this.keywords = d.keywords();
            this.classification = d.classification();
            this.maxLimit = d.maxLimit();
            this.filters = new TreeSet<>(d.mandatoryFilters());
            d.attributes().forEach(a -> attributes.put(a.ref(), new AttrState(a)));
        }
    }

    private static final class AttrState {
        final AttributeDescriptor d;
        String meaning;
        boolean sensitive;
        @Nullable Classification explicit;
        boolean enabled = true;
        final List<PolicyProvenance> provenance = new ArrayList<>();

        AttrState(AttributeDescriptor d) {
            this.d = d;
            this.meaning = d.meaning();
            this.sensitive = d.sensitive();
            this.explicit = d.classification() == Classification.INHERIT ? null : d.classification();
        }
    }

    private static final class OpState {
        final OperationDescriptor d;
        String description;
        List<String> keywords;
        Classification classification;
        boolean readOnly;
        @Nullable Integer maxLimit;
        boolean enabled = true;
        final List<PolicyProvenance> provenance = new ArrayList<>();

        OpState(OperationDescriptor d) {
            this.d = d;
            this.description = d.intent();
            this.keywords = d.keywords();
            this.classification = d.classification();
            this.readOnly = d.readOnly();
        }
    }

    private sealed interface Target permits EntityTarget, AttrTarget, OpTarget {
    }

    private record EntityTarget(EntityState state) implements Target {
    }

    private record AttrTarget(EntityState entity, AttrState state) implements Target {
    }

    private record OpTarget(OpState state) implements Target {
    }

    private final class Merge {
        final ScannedCatalog scanned;
        final Map<CatalogElementRef, EntityState> entities = new LinkedHashMap<>();
        final Map<CatalogElementRef, OpState> operations = new LinkedHashMap<>();
        final List<ScanIssue> issues = new ArrayList<>();
        final List<PolicyLayerStatus> statuses = new ArrayList<>();

        Merge(ScannedCatalog scanned) {
            this.scanned = scanned;
            scanned.entities().forEach((ref, d) -> entities.put(ref, new EntityState(d)));
            scanned.operations().forEach((ref, d) -> operations.put(ref, new OpState(d)));
        }

        // ---- document layers ----

        void applyDocument(PolicyLayerInput.DocumentLayer doc) {
            PolicyLayer layer = doc.layer();
            String source = doc.sourceId();
            List<Map.Entry<Target, PolicyOverride>> resolved = new ArrayList<>();
            List<String> ambiguities = new ArrayList<>();
            for (Map.Entry<PolicyKey, PolicyOverride> entry : doc.document().overrides().entrySet()) {
                PolicyKey key = entry.getKey();
                switch (key) {
                    case PolicyKey.Canonical canonical -> {
                        Target target = resolve(canonical.ref());
                        if (target == null) {
                            unknownRef(layer, source, key.text(), "no scanned element with this reference");
                        } else {
                            resolved.add(Map.entry(target, entry.getValue()));
                        }
                    }
                    case PolicyKey.Shorthand shorthand -> {
                        List<OpState> matches = operations.values().stream()
                                .filter(o -> o.d.methodName().equals(shorthand.methodName())
                                        && (o.d.declaringType().equals(shorthand.className())
                                        || o.d.invocationType().equals(shorthand.className())))
                                .toList();
                        if (matches.isEmpty()) {
                            unknownRef(layer, source, key.text(), "no scanned operation matches this shorthand");
                        } else if (matches.size() > 1) {
                            String refs = String.join(", ", matches.stream().map(o -> o.d.ref().toString()).toList());
                            String msg = "shorthand " + key.text() + " is ambiguous (overloaded); use one of: " + refs;
                            ambiguities.add(msg);
                            issues.add(new ScanIssue(ScanIssueCode.POLICY_SHORTHAND_AMBIGUOUS,
                                    ScanIssue.Severity.ERROR, null, source + " " + key.text(), msg, true));
                        } else {
                            resolved.add(Map.entry(new OpTarget(matches.getFirst()), entry.getValue()));
                        }
                    }
                }
            }
            if (!ambiguities.isEmpty()) {
                failClosed(layer, source, String.join("; ", ambiguities));
                return;
            }
            for (Map.Entry<Target, PolicyOverride> r : resolved) {
                switch (r.getKey()) {
                    case EntityTarget t -> applyToEntity(t.state(), r.getValue(), layer, source);
                    case AttrTarget t -> applyToAttribute(t.entity(), t.state(), r.getValue(), layer, source);
                    case OpTarget t -> applyToOperation(t.state(), r.getValue(), layer, source);
                }
            }
            statuses.add(new PolicyLayerStatus(layer, source, true, null, doc.document().fingerprint()));
        }

        @Nullable Target resolve(CatalogElementRef ref) {
            return switch (ref.kind()) {
                case ENTITY -> {
                    EntityState e = entities.get(ref);
                    yield e == null ? null : new EntityTarget(e);
                }
                case ATTR -> {
                    int hash = ref.value().indexOf('#');
                    EntityState e = hash < 0 ? null
                            : entities.get(CatalogElementRef.entity(ref.value().substring(0, hash)));
                    AttrState a = e == null ? null : e.attributes.get(ref);
                    yield e == null || a == null ? null : new AttrTarget(e, a);
                }
                case OP -> {
                    OpState o = operations.get(ref);
                    yield o == null ? null : new OpTarget(o);
                }
                default -> null;
            };
        }

        void applyToEntity(EntityState e, PolicyOverride o, PolicyLayer layer, String source) {
            CatalogElementRef ref = e.d.ref();
            notApplicable(ref, o.sensitive() != null, "sensitive", layer, source);
            notApplicable(ref, o.readOnly() != null, "readOnly", layer, source);
            if (Boolean.FALSE.equals(o.enabled()) && e.enabled) {
                e.enabled = false;
                e.provenance.add(new PolicyProvenance("enabled", layer, source, "false", o.reason()));
            }
            if (o.descriptionOverride() != null && !o.descriptionOverride().isBlank()) {
                e.description = o.descriptionOverride();
                e.provenance.add(new PolicyProvenance("description", layer, source, summary(e.description), o.reason()));
            }
            if (o.keywords() != null && !o.keywords().isEmpty()) {
                e.keywords = o.keywords();
                e.provenance.add(new PolicyProvenance("keywords", layer, source, String.join(",", e.keywords), o.reason()));
            }
            if (o.classification() != null) {
                Classification next = classify(ref, e.classification, o.classification(), o, layer, source);
                if (next != e.classification) {
                    e.classification = next;
                    e.provenance.add(new PolicyProvenance("classification", layer, source, next.name(), o.reason()));
                }
            }
            if (o.maxLimit() != null && o.maxLimit() < e.maxLimit) {
                e.maxLimit = o.maxLimit();
                e.provenance.add(new PolicyProvenance("maxLimit", layer, source, Integer.toString(e.maxLimit), o.reason()));
            }
            if (o.mandatoryFilters() != null && !e.filters.containsAll(o.mandatoryFilters())) {
                e.filters.addAll(o.mandatoryFilters());
                e.provenance.add(new PolicyProvenance("mandatoryFilters", layer, source, String.join(",", e.filters),
                        o.reason()));
            }
        }

        void applyToAttribute(EntityState e, AttrState a, PolicyOverride o, PolicyLayer layer, String source) {
            CatalogElementRef ref = a.d.ref();
            notApplicable(ref, o.keywords() != null, "keywords", layer, source);
            notApplicable(ref, o.maxLimit() != null, "maxLimit", layer, source);
            notApplicable(ref, o.mandatoryFilters() != null, "mandatoryFilters", layer, source);
            notApplicable(ref, o.readOnly() != null, "readOnly", layer, source);
            if (Boolean.FALSE.equals(o.enabled()) && a.enabled) {
                a.enabled = false;
                a.provenance.add(new PolicyProvenance("enabled", layer, source, "false", o.reason()));
            }
            if (o.descriptionOverride() != null && !o.descriptionOverride().isBlank()) {
                a.meaning = o.descriptionOverride();
                a.provenance.add(new PolicyProvenance("meaning", layer, source, summary(a.meaning), o.reason()));
            }
            if (o.sensitive() != null && o.sensitive() != a.sensitive) {
                if (o.sensitive()) {
                    a.sensitive = true;
                    a.provenance.add(new PolicyProvenance("sensitive", layer, source, "true", o.reason()));
                } else if (layer == PolicyLayer.OVERLAY && o.declassify()) {
                    a.sensitive = false;
                    a.provenance.add(new PolicyProvenance("sensitive", layer, source, "false (declassified)", o.reason()));
                } else {
                    issues.add(ScanIssue.of(ScanIssueCode.POLICY_DECLASSIFY_REJECTED, ref, "layer " + layer + " ("
                            + source + ") cannot lower sensitivity without an OVERLAY declassify flag; ignored", false));
                }
            }
            if (o.classification() != null) {
                Classification current = a.explicit != null ? a.explicit : e.classification;
                Classification next = classify(ref, current, o.classification(), o, layer, source);
                if (next != current) {
                    a.explicit = next;
                    a.provenance.add(new PolicyProvenance("classification", layer, source, next.name(), o.reason()));
                }
            }
        }

        void applyToOperation(OpState op, PolicyOverride o, PolicyLayer layer, String source) {
            CatalogElementRef ref = op.d.ref();
            notApplicable(ref, o.sensitive() != null, "sensitive", layer, source);
            notApplicable(ref, o.mandatoryFilters() != null, "mandatoryFilters", layer, source);
            if (Boolean.FALSE.equals(o.enabled()) && op.enabled) {
                op.enabled = false;
                op.provenance.add(new PolicyProvenance("enabled", layer, source, "false", o.reason()));
            }
            if (o.readOnly() != null) {
                if (o.readOnly() && !op.readOnly) {
                    if (op.enabled) {
                        op.enabled = false;
                        op.provenance.add(new PolicyProvenance("enabled", layer, source,
                                "false (layer requires read-only but the action writes)", o.reason()));
                    }
                } else if (!o.readOnly() && op.readOnly) {
                    issues.add(ScanIssue.of(ScanIssueCode.POLICY_READ_ONLY_LOOSENING, ref, "layer " + layer + " ("
                            + source + ") cannot turn a read action into a write; readOnly=false ignored", false));
                }
            }
            if (o.descriptionOverride() != null && !o.descriptionOverride().isBlank()) {
                op.description = o.descriptionOverride();
                op.provenance.add(new PolicyProvenance("description", layer, source, summary(op.description), o.reason()));
            }
            if (o.keywords() != null && !o.keywords().isEmpty()) {
                op.keywords = o.keywords();
                op.provenance.add(new PolicyProvenance("keywords", layer, source, String.join(",", op.keywords), o.reason()));
            }
            if (o.classification() != null) {
                Classification next = classify(ref, op.classification, o.classification(), o, layer, source);
                if (next != op.classification) {
                    op.classification = next;
                    op.provenance.add(new PolicyProvenance("classification", layer, source, next.name(), o.reason()));
                }
            }
            if (o.maxLimit() != null && (op.maxLimit == null || o.maxLimit() < op.maxLimit)) {
                op.maxLimit = o.maxLimit();
                op.provenance.add(new PolicyProvenance("maxLimit", layer, source, Integer.toString(op.maxLimit), o.reason()));
            }
        }

        Classification classify(CatalogElementRef ref, Classification current, Classification requested,
                                PolicyOverride o, PolicyLayer layer, String source) {
            if (requested.compareTo(current) >= 0) {
                return requested;
            }
            if (layer == PolicyLayer.OVERLAY && o.declassify()) {
                return requested;
            }
            issues.add(ScanIssue.of(ScanIssueCode.POLICY_DECLASSIFY_REJECTED, ref, "layer " + layer + " (" + source
                    + ") cannot lower classification from " + current + " to " + requested
                    + " without an OVERLAY declassify flag; ignored", false));
            return current;
        }

        void notApplicable(CatalogElementRef ref, boolean present, String field, PolicyLayer layer, String source) {
            if (present) {
                issues.add(ScanIssue.of(ScanIssueCode.POLICY_FIELD_NOT_APPLICABLE, ref, "field " + field
                        + " does not apply to " + ref.kind() + " elements (layer " + layer + ", " + source + "); ignored",
                        false));
            }
        }

        void unknownRef(PolicyLayer layer, String source, String key, String why) {
            ScanIssue issue = ScanIssue.ofSubject(ScanIssueCode.POLICY_REF_UNKNOWN, source + " " + key,
                    why + " (layer " + layer + "); configuration can only restrict elements exposed in code", false);
            issues.add(strict ? issue.withSeverity(ScanIssue.Severity.ERROR) : issue);
        }

        // ---- fail closed ----

        void failClosed(PolicyLayer layer, String source, String error) {
            String reason = "policy layer " + layer + " (" + source + ") is invalid; everything disabled until fixed";
            for (OpState op : operations.values()) {
                if (op.enabled) {
                    op.enabled = false;
                    op.provenance.add(new PolicyProvenance("enabled", layer, source, "false (fail closed)", reason));
                }
            }
            for (EntityState e : entities.values()) {
                if (e.enabled) {
                    e.enabled = false;
                    e.provenance.add(new PolicyProvenance("enabled", layer, source, "false (fail closed)", reason));
                }
            }
            issues.add(ScanIssue.ofSubject(ScanIssueCode.POLICY_LAYER_INVALID, layer + " " + source,
                    reason + ": " + error, true));
            statuses.add(new PolicyLayerStatus(layer, source, false, error, null));
        }

        // ---- kill switches ----

        void applyKillSwitch(PolicyLayerInput.KillSwitchLayer kill) {
            PolicyLayer layer = PolicyLayer.KILL_SWITCH;
            String source = kill.sourceId();
            if (kill.global()) {
                operations.values().forEach(op -> disableOp(op, layer, source, kill.reason()));
                entities.values().forEach(e -> disableEntity(e, layer, source, kill.reason()));
            }
            for (String toolName : new TreeSet<>(kill.toolNames())) {
                List<OpState> matches = operations.values().stream().filter(o -> o.d.toolName().equals(toolName)).toList();
                if (matches.isEmpty()) {
                    unknownRef(layer, source, toolName, "no scanned operation has this tool name");
                }
                matches.forEach(op -> disableOp(op, layer, source, kill.reason()));
            }
            List<CatalogElementRef> refs = kill.elements().stream()
                    .sorted(Comparator.comparing(CatalogElementRef::toString)).toList();
            for (CatalogElementRef ref : refs) {
                Target target = resolve(ref);
                switch (target) {
                    case null -> unknownRef(layer, source, ref.toString(), "no scanned element with this reference");
                    case OpTarget t -> disableOp(t.state(), layer, source, kill.reason());
                    case AttrTarget t -> {
                        if (t.state().enabled) {
                            t.state().enabled = false;
                            t.state().provenance.add(new PolicyProvenance("enabled", layer, source, "false", kill.reason()));
                        }
                    }
                    case EntityTarget t -> {
                        disableEntity(t.state(), layer, source, kill.reason());
                        operations.values().stream().filter(o -> ref.equals(o.d.entity()))
                                .forEach(op -> disableOp(op, layer, source, kill.reason()));
                    }
                }
            }
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("global", kill.global());
            canonical.put("toolNames", new ArrayList<>(new TreeSet<>(kill.toolNames())));
            canonical.put("elements", refs.stream().map(CatalogElementRef::toString).toList());
            canonical.put("reason", kill.reason());
            statuses.add(new PolicyLayerStatus(layer, source, true, null, Sha256.of(CanonicalJson.write(canonical))));
        }

        void disableOp(OpState op, PolicyLayer layer, String source, String reason) {
            if (op.enabled) {
                op.enabled = false;
                op.provenance.add(new PolicyProvenance("enabled", layer, source, "false", reason));
            }
        }

        void disableEntity(EntityState e, PolicyLayer layer, String source, String reason) {
            if (e.enabled) {
                e.enabled = false;
                e.provenance.add(new PolicyProvenance("enabled", layer, source, "false", reason));
            }
        }

        // ---- result ----

        EffectiveCatalog result(long generation) {
            Map<CatalogElementRef, EffectiveEntity> outEntities = new LinkedHashMap<>();
            for (EntityState e : entities.values()) {
                Map<CatalogElementRef, EffectiveAttribute> attrs = new LinkedHashMap<>();
                for (AttrState a : e.attributes.values()) {
                    Classification c = a.explicit != null ? a.explicit : e.classification;
                    attrs.put(a.d.ref(), new EffectiveAttribute(a.d.ref(), a.d, a.meaning, a.sensitive, c, a.enabled,
                            a.provenance));
                }
                outEntities.put(e.d.ref(), new EffectiveEntity(e.d.ref(), e.d, e.description, e.keywords,
                        e.classification, Math.min(e.maxLimit, globalMaxLimit), new ArrayList<>(e.filters), e.enabled,
                        attrs, e.provenance));
            }
            Map<CatalogElementRef, EffectiveOperation> outOps = new LinkedHashMap<>();
            for (OpState op : operations.values()) {
                int cap = op.maxLimit == null ? globalMaxLimit : Math.min(op.maxLimit, globalMaxLimit);
                outOps.put(op.d.ref(), new EffectiveOperation(op.d.ref(), op.d, op.d.toolName(), op.description,
                        op.keywords, op.readOnly, op.classification, cap, op.enabled, op.provenance));
            }
            List<ScanIssue> allIssues = new ArrayList<>(scanned.issues());
            allIssues.addAll(issues);
            List<Map<String, @Nullable Object>> layerCanon = new ArrayList<>();
            for (PolicyLayerStatus s : statuses) {
                Map<String, @Nullable Object> m = new LinkedHashMap<>();
                m.put("layer", s.layer());
                m.put("source", s.source());
                m.put("valid", s.valid());
                m.put("fingerprint", s.fingerprint());
                m.put("error", s.error());
                layerCanon.add(m);
            }
            layerCanon.add(Map.of("globalMaxLimit", globalMaxLimit));
            return new EffectiveCatalog(generation, scanned.scanFingerprint(), Sha256.of(CanonicalJson.write(layerCanon)),
                    outEntities, outOps, allIssues, statuses);
        }
    }

    private static String summary(String text) {
        return "<" + text.length() + " chars>";
    }
}
