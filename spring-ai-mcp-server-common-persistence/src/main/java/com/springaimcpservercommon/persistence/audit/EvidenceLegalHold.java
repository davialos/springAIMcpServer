package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Legal hold on a subject's evidence ({@code dai_evidence_legal_hold}): while active, the subject's key cannot be
 * shredded and evidence partitions holding the subject's rows are not dropped by retention.
 */
@Entity
@Table(name = "dai_evidence_legal_hold")
public class EvidenceLegalHold {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subject_key_id", nullable = false, updatable = false)
    private String subjectKeyId;

    @Column(name = "reference", nullable = false, updatable = false)
    private String reference;

    @Column(name = "placed_by", nullable = false, updatable = false)
    private UUID placedBy;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "released_by")
    private @Nullable UUID releasedBy;

    @Column(name = "released_at")
    private @Nullable Instant releasedAt;

    /** For JPA only. */
    protected EvidenceLegalHold() {
    }

    static EvidenceLegalHold place(String subjectKeyId, String reference, UUID placedBy, Instant now) {
        EvidenceLegalHold h = new EvidenceLegalHold();
        h.id = Ids.newId();
        h.subjectKeyId = Checks.text(subjectKeyId, "subjectKeyId", 256);
        h.reference = Checks.text(reference, "reference", 256);
        h.placedBy = Checks.required(placedBy, "placedBy");
        h.placedAt = UtcTimes.micros(now);
        return h;
    }

    /**
     * Releases the hold ({@code ck_evidence_legal_hold_release}: time and principal together).
     *
     * @param by  releasing principal
     * @param now current time
     * @return {@code true} if the hold was active
     */
    boolean release(UUID by, Instant now) {
        if (releasedAt != null) {
            return false;
        }
        releasedBy = Checks.required(by, "by");
        releasedAt = UtcTimes.micros(now);
        return true;
    }

    /** @return hold id */
    public UUID getId() {
        return id;
    }

    /** @return held subject key id */
    public String getSubjectKeyId() {
        return subjectKeyId;
    }

    /** @return case or matter reference */
    public String getReference() {
        return reference;
    }

    /** @return placing principal */
    public UUID getPlacedBy() {
        return placedBy;
    }

    /** @return placement time */
    public Instant getPlacedAt() {
        return placedAt;
    }

    /** @return releasing principal, if released */
    public @Nullable UUID getReleasedBy() {
        return releasedBy;
    }

    /** @return release time, if released */
    public @Nullable Instant getReleasedAt() {
        return releasedAt;
    }

    /** @return whether the hold is active */
    public boolean isActive() {
        return releasedAt == null;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof EvidenceLegalHold other && id.equals(other.getId()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
