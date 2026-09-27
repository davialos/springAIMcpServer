package com.springaimcpservercommon.persistence.config;

import com.springaimcpservercommon.core.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable-after-submit version of a resource's content ({@code dai_resource_revision}) and the aggregate that
 * enforces the revision lifecycle of LLD-09 §2 (see {@link RevisionState} for the transition table).
 *
 * <p>Invariants enforced here (the V2 trigger {@code trg_resource_revision_immutable} is defence in depth):
 * <ul>
 *   <li>spec, schema version and summary can only change in DRAFT, and only by the author;</li>
 *   <li>{@code spec_hash} always equals the {@link CanonicalSpec} hash of {@code spec};</li>
 *   <li>every state change follows {@link RevisionState#canTransitionTo};</li>
 *   <li>timestamps of the lifecycle ({@code submitted_at}, {@code approved_at}, {@code published_at},
 *       {@code retired_at}) are set by the transitions.</li>
 * </ul>
 * Cross-revision rules (one live revision per resource, contiguous snapshot generations, four-eyes review quorum)
 * are coordinated by {@link ConfigStore}.
 */
@Entity
@Table(name = "dai_resource_revision")
public class ResourceRevision {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "revision_no", nullable = false, updatable = false)
    private int revisionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private RevisionState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", nullable = false)
    private String spec;

    @Column(name = "spec_schema_version", nullable = false)
    private int specSchemaVersion;

    @Column(name = "spec_hash", nullable = false)
    private String specHash;

    @Column(name = "scan_fingerprint")
    private @Nullable String scanFingerprint;

    @Column(name = "change_summary")
    private @Nullable String changeSummary;

    @Column(name = "risk_score")
    private @Nullable Short riskScore;

    @Column(name = "based_on_id", updatable = false)
    private @Nullable UUID basedOnId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private @Nullable Instant submittedAt;

    @Column(name = "approved_at")
    private @Nullable Instant approvedAt;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private @Nullable Long rowVersion;

    /** For JPA only. */
    protected ResourceRevision() {
    }

    private ResourceRevision(UUID resourceId, int revisionNo, CanonicalSpec spec, DraftContent content,
                             @Nullable UUID basedOnId, UUID authorId, Instant now) {
        this.id = Ids.newId();
        this.resourceId = resourceId;
        this.revisionNo = revisionNo;
        this.state = RevisionState.DRAFT;
        this.basedOnId = basedOnId;
        this.authorId = authorId;
        this.createdAt = now;
        applyContent(spec, content);
    }

    /**
     * Creates a draft revision.
     *
     * @param resource   the resource (must not be retired)
     * @param revisionNo next revision number of the resource (≥ 1)
     * @param content    authored content
     * @param basedOnId  revision this draft was derived from, if any
     * @param authorId   author
     * @param now        creation time
     * @return the new draft (not yet persisted)
     * @throws IllegalArgumentException if the spec is not a JSON object
     */
    public static ResourceRevision draft(Resource resource, int revisionNo, DraftContent content,
                                         @Nullable UUID basedOnId, UUID authorId, Instant now) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(authorId, "authorId");
        Objects.requireNonNull(now, "now");
        resource.requireNotRetired();
        if (revisionNo < 1) {
            throw new IllegalArgumentException("revisionNo must be >= 1");
        }
        return new ResourceRevision(resource.getId(), revisionNo, CanonicalSpec.of(content.specJson()), content,
                basedOnId, authorId, now);
    }

    /**
     * Replaces the content of a draft.
     *
     * @param editorId acting principal; must be the author
     * @param content  new content
     * @throws ConfigLifecycleException if not a draft or the editor is not the author
     */
    public void editDraft(UUID editorId, DraftContent content) {
        Objects.requireNonNull(editorId, "editorId");
        Objects.requireNonNull(content, "content");
        requireDraft();
        if (!authorId.equals(editorId)) {
            throw new ConfigLifecycleException("only the author may edit draft " + id);
        }
        applyContent(CanonicalSpec.of(content.specJson()), content);
    }

    private void applyContent(CanonicalSpec canonical, DraftContent content) {
        this.spec = canonical.json();
        this.specHash = canonical.hash();
        this.specSchemaVersion = content.specSchemaVersion();
        this.changeSummary = content.changeSummary();
        this.scanFingerprint = content.scanFingerprint();
    }

    /**
     * Fails unless the revision is a draft (references and dependencies can only change then).
     *
     * @throws ConfigLifecycleException otherwise
     */
    public void requireDraft() {
        if (state != RevisionState.DRAFT) {
            throw new ConfigLifecycleException("revision " + id + " is " + state + "; only DRAFT is editable");
        }
    }

    /**
     * DRAFT → IN_REVIEW. From now on the content is immutable.
     *
     * @param riskScore optional risk score 0..100 computed by the caller
     * @param now       submit time
     */
    public void submit(@Nullable Integer riskScore, Instant now) {
        if (riskScore != null && (riskScore < 0 || riskScore > 100)) {
            throw new IllegalArgumentException("riskScore must be within 0..100");
        }
        moveTo(RevisionState.IN_REVIEW);
        this.riskScore = riskScore == null ? null : riskScore.shortValue();
        this.submittedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * IN_REVIEW → APPROVED (quorum reached, or approval not required by policy).
     *
     * @param now approval time
     */
    public void approve(Instant now) {
        moveTo(RevisionState.APPROVED);
        this.approvedAt = Objects.requireNonNull(now, "now");
    }

    /** IN_REVIEW → REJECTED (a reviewer rejected or requested changes). */
    public void reject() {
        moveTo(RevisionState.REJECTED);
    }

    /** IN_REVIEW or APPROVED → STALE (approval expired before publish). */
    public void expire() {
        moveTo(RevisionState.STALE);
    }

    /**
     * APPROVED → PUBLISHED (publish) or SUPERSEDED → PUBLISHED (rollback republish).
     *
     * @param now time the revision goes live
     */
    public void publish(Instant now) {
        moveTo(RevisionState.PUBLISHED);
        this.publishedAt = Objects.requireNonNull(now, "now");
    }

    /** PUBLISHED or DEPRECATED → SUPERSEDED (replaced by a newer publish or removed by a rollback). */
    public void supersede() {
        moveTo(RevisionState.SUPERSEDED);
    }

    /** PUBLISHED → DEPRECATED (still served, flagged for removal). */
    public void deprecate() {
        moveTo(RevisionState.DEPRECATED);
    }

    /**
     * DEPRECATED → RETIRED (removed from the live set for good).
     *
     * @param now retire time
     */
    public void retire(Instant now) {
        moveTo(RevisionState.RETIRED);
        this.retiredAt = Objects.requireNonNull(now, "now");
    }

    private void moveTo(RevisionState target) {
        if (!state.canTransitionTo(target)) {
            throw new ConfigLifecycleException("revision " + id + " cannot go from " + state + " to " + target);
        }
        this.state = target;
    }

    /**
     * Recomputes the canonical form of the stored spec and checks it against {@code spec_hash}
     * (integrity check after loading, e.g. by the snapshot watcher).
     *
     * @return {@code true} if the stored hash matches the stored spec
     */
    public boolean specHashMatches() {
        return CanonicalSpec.of(spec).hash().equals(specHash);
    }

    /**
     * Immutable snapshot of this row.
     *
     * @return the view
     */
    public RevisionView view() {
        return new RevisionView(id, resourceId, revisionNo, state, spec, specSchemaVersion, specHash, scanFingerprint,
                changeSummary, riskScore == null ? null : riskScore.intValue(), basedOnId, authorId, createdAt,
                submittedAt, approvedAt, publishedAt, retiredAt, getRowVersion());
    }

    public UUID getId() {
        return id;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public int getRevisionNo() {
        return revisionNo;
    }

    public RevisionState getState() {
        return state;
    }

    public String getSpec() {
        return spec;
    }

    public String getSpecHash() {
        return specHash;
    }

    public int getSpecSchemaVersion() {
        return specSchemaVersion;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public @Nullable Instant getPublishedAt() {
        return publishedAt;
    }

    public @Nullable Instant getApprovedAt() {
        return approvedAt;
    }

    public long getRowVersion() {
        return rowVersion == null ? 0L : rowVersion;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ResourceRevision other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
