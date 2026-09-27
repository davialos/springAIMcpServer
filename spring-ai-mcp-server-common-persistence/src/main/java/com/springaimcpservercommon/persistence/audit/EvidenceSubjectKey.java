package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.persistence.support.Checks;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Per-subject wrapped data key for evidence ({@code dai_evidence_subject_key}). Shredding nulls the wrapped key
 * ({@code ck_evidence_subject_key_shred}: shredded exactly when the key is gone), which makes all evidence of the
 * subject unreadable while the audit hash chain stays valid.
 */
@Entity
@Table(name = "dai_evidence_subject_key")
public class EvidenceSubjectKey {

    @Id
    @Column(name = "subject_key_id", nullable = false, updatable = false)
    private String subjectKeyId;

    @Column(name = "principal_id", updatable = false)
    private @Nullable UUID principalId;

    @Column(name = "kek_ref", nullable = false, updatable = false)
    private String kekRef;

    @Column(name = "wrapped_key")
    private byte @Nullable [] wrappedKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "shredded_at")
    private @Nullable Instant shreddedAt;

    @Column(name = "shredded_by")
    private @Nullable UUID shreddedBy;

    /** For JPA only. */
    protected EvidenceSubjectKey() {
    }

    static EvidenceSubjectKey create(String subjectKeyId, @Nullable UUID principalId, String kekRef,
                                     byte[] wrappedKey, Instant now) {
        EvidenceSubjectKey k = new EvidenceSubjectKey();
        k.subjectKeyId = Checks.text(subjectKeyId, "subjectKeyId", 256);
        k.principalId = principalId;
        k.kekRef = Checks.text(kekRef, "kekRef", 512);
        if (wrappedKey.length == 0) {
            throw new IllegalArgumentException("wrappedKey must not be empty");
        }
        k.wrappedKey = wrappedKey.clone();
        k.createdAt = UtcTimes.micros(now);
        return k;
    }

    /**
     * Destroys the wrapped key.
     *
     * @param actorId principal requesting the erasure, if any
     * @param now     current time
     * @return {@code true} if the key was present and is now shredded
     */
    boolean shred(@Nullable UUID actorId, Instant now) {
        if (wrappedKey == null) {
            return false;
        }
        Arrays.fill(wrappedKey, (byte) 0);
        wrappedKey = null;
        shreddedAt = UtcTimes.micros(now);
        shreddedBy = actorId;
        return true;
    }

    /**
     * The wrapped key for decryption/encryption.
     *
     * @return the key
     * @throws EvidenceShreddedException if the key has been shredded
     */
    WrappedDataKey wrappedDataKey() {
        byte[] key = wrappedKey;
        if (key == null) {
            throw new EvidenceShreddedException(subjectKeyId);
        }
        return new WrappedDataKey(subjectKeyId, kekRef, key.clone());
    }

    /** @return subject key id */
    public String getSubjectKeyId() {
        return subjectKeyId;
    }

    /** @return principal the subject corresponds to, if any */
    public @Nullable UUID getPrincipalId() {
        return principalId;
    }

    /** @return KEK reference */
    public String getKekRef() {
        return kekRef;
    }

    /** @return whether the key has been shredded */
    public boolean isShredded() {
        return wrappedKey == null;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return shredding time, if shredded */
    public @Nullable Instant getShreddedAt() {
        return shreddedAt;
    }

    /** @return principal who shredded, if any */
    public @Nullable UUID getShreddedBy() {
        return shreddedBy;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof EvidenceSubjectKey other && subjectKeyId.equals(other.getSubjectKeyId()));
    }

    @Override
    public int hashCode() {
        return subjectKeyId.hashCode();
    }
}
