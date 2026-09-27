package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.persistence.support.PageRequest;
import com.springaimcpservercommon.persistence.support.SqlStates;
import com.springaimcpservercommon.persistence.support.StoreSupport;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import com.springaimcpservercommon.persistence.unit.DaiStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Evidence tier of the audit (ADR-0018, LLD-10 §4.1): envelope-encrypted full content per data subject,
 * crypto-shredding and legal holds. Access control (auditor-only, reason required) and auditing of reads are the
 * caller's responsibility; this store never logs content.
 *
 * <ul>
 *   <li>{@link #write} encrypts with the subject's data key (created on first use through the
 *       {@link DataKeyProvider}); the subject key row is share-locked so a concurrent shred waits.</li>
 *   <li>{@link #shred} nulls the wrapped key (refused under an active legal hold): every evidence row of the subject
 *       becomes unreadable while the audit hash chain stays valid.</li>
 * </ul>
 */
public final class EvidenceStore {

    private final StoreSupport db;
    private final EvidenceCipher cipher;
    private final DataKeyProvider keyProvider;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param store       the persistence unit
     * @param cipher      evidence cipher (normally {@link AesGcmEvidenceCipher})
     * @param keyProvider host KMS adapter used to create subject keys
     * @param clock       clock
     */
    public EvidenceStore(DaiStore store, EvidenceCipher cipher, DataKeyProvider keyProvider, Clock clock) {
        this.db = new StoreSupport(store);
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the subject's key, creating it through the KMS when absent (idempotent across nodes).
     *
     * @param subjectKeyId subject key id
     * @param principalId  principal of the subject, if any
     * @return the key row
     * @throws EvidenceShreddedException if the subject's key was shredded
     */
    public EvidenceSubjectKey ensureSubjectKey(String subjectKeyId, @Nullable UUID principalId) {
        Optional<EvidenceSubjectKey> existing = findSubjectKey(subjectKeyId);
        if (existing.isEmpty()) {
            DataKeyProvider.GeneratedDataKey generated = keyProvider.generateDataKey(subjectKeyId);
            Arrays.fill(generated.plaintextKey(), (byte) 0); // only the wrapped form is needed here
            EvidenceSubjectKey created = EvidenceSubjectKey.create(subjectKeyId, principalId, generated.kekRef(),
                    generated.wrappedKey(), clock.instant());
            try {
                db.writeNew(em -> {
                    em.persist(created);
                    return created;
                });
                return created;
            } catch (RuntimeException e) {
                if (!SqlStates.isUniqueViolation(e)) {
                    throw e;
                }
                existing = findSubjectKey(subjectKeyId);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        EvidenceSubjectKey key = existing.get();
        if (key.isShredded()) {
            throw new EvidenceShreddedException(subjectKeyId);
        }
        return key;
    }

    /**
     * Finds a subject key.
     *
     * @param subjectKeyId subject key id
     * @return the key row, if any
     */
    public Optional<EvidenceSubjectKey> findSubjectKey(String subjectKeyId) {
        return db.read(em -> Optional.ofNullable(em.find(EvidenceSubjectKey.class, subjectKeyId)));
    }

    /**
     * Encrypts and stores evidence.
     *
     * @param evidence the evidence
     * @return the evidence id
     * @throws EvidenceShreddedException if the subject's key was shredded
     */
    public UUID write(NewEvidence evidence) {
        ensureSubjectKey(evidence.subjectKeyId(), evidence.principalId());
        Instant occurredAt = UtcTimes.micros(evidence.occurredAt());
        UUID id = Ids.newId(occurredAt.toEpochMilli());
        return db.write(em -> {
            EvidenceSubjectKey key = em.find(EvidenceSubjectKey.class, evidence.subjectKeyId(),
                    LockModeType.PESSIMISTIC_READ);
            if (key == null) {
                throw new NoSuchElementException("subject key " + evidence.subjectKeyId() + " not found");
            }
            byte[] aad = AuditEvidence.associatedData(id, evidence.auditEventId(), evidence.subjectKeyId(),
                    evidence.contentType());
            SealedEvidence sealed = cipher.seal(key.wrappedDataKey(), evidence.content(), aad);
            em.persist(AuditEvidence.of(id, occurredAt, evidence.auditEventId(), evidence.subjectKeyId(),
                    evidence.contentType(), sealed, evidence.retentionUntil()));
            return id;
        });
    }

    /**
     * Decrypts one evidence row. The caller must have authorised and audited the read.
     *
     * @param evidenceId evidence id
     * @return the plaintext content
     * @throws NoSuchElementException      if the evidence does not exist
     * @throws EvidenceShreddedException   if the subject's key was shredded
     * @throws EvidenceIntegrityException  if the ciphertext fails authentication
     */
    public byte[] read(UUID evidenceId) {
        return db.read(em -> {
            AuditEvidence evidence = em.createQuery("select e from AuditEvidence e where e.id = :id", AuditEvidence.class)
                    .setParameter("id", evidenceId)
                    .getResultStream()
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("evidence " + evidenceId + " not found"));
            EvidenceSubjectKey key = em.find(EvidenceSubjectKey.class, evidence.getSubjectKeyId());
            if (key == null) {
                throw new EvidenceShreddedException(evidence.getSubjectKeyId());
            }
            return cipher.open(key.wrappedDataKey(), evidence.sealed(), evidence.associatedData());
        });
    }

    /**
     * Metadata (no content) of the evidence attached to an audit event.
     *
     * @param auditEventId audit event id
     * @return evidence rows in time order
     */
    public List<AuditEvidence> evidenceOf(UUID auditEventId) {
        return db.read(em -> em.createQuery("select e from AuditEvidence e where e.auditEventId = :a "
                        + "order by e.occurredAt, e.id", AuditEvidence.class)
                .setParameter("a", auditEventId)
                .setMaxResults(PageRequest.MAX_LIMIT)
                .getResultList());
    }

    /**
     * Crypto-shreds a subject: destroys the wrapped data key so all its evidence becomes unreadable.
     *
     * @param subjectKeyId subject key id
     * @param actorId      principal requesting the erasure, if any
     * @return {@code true} if the key existed and was shredded now
     * @throws LegalHoldActiveException if an active legal hold covers the subject
     */
    public boolean shred(String subjectKeyId, @Nullable UUID actorId) {
        Instant now = clock.instant();
        return db.write(em -> {
            EvidenceSubjectKey key = em.find(EvidenceSubjectKey.class, subjectKeyId, LockModeType.PESSIMISTIC_WRITE);
            if (key == null) {
                return false;
            }
            if (activeHold(em, subjectKeyId)) {
                throw new LegalHoldActiveException(subjectKeyId);
            }
            return key.shred(actorId, now);
        });
    }

    /**
     * Places a legal hold on a subject (the subject key must exist).
     *
     * @param subjectKeyId subject key id
     * @param reference    case or matter reference
     * @param placedBy     placing principal
     * @return the hold
     */
    public EvidenceLegalHold placeLegalHold(String subjectKeyId, String reference, UUID placedBy) {
        EvidenceLegalHold hold = EvidenceLegalHold.place(subjectKeyId, reference, placedBy, clock.instant());
        db.writeVoid(em -> {
            if (em.find(EvidenceSubjectKey.class, subjectKeyId, LockModeType.PESSIMISTIC_WRITE) == null) {
                throw new NoSuchElementException("subject key " + subjectKeyId + " not found");
            }
            em.persist(hold);
        });
        return hold;
    }

    /**
     * Releases a legal hold.
     *
     * @param holdId     hold id
     * @param releasedBy releasing principal
     * @return {@code true} if the hold was active
     * @throws NoSuchElementException if the hold does not exist
     */
    public boolean releaseLegalHold(UUID holdId, UUID releasedBy) {
        Instant now = clock.instant();
        return db.write(em -> {
            EvidenceLegalHold hold = em.find(EvidenceLegalHold.class, holdId);
            if (hold == null) {
                throw new NoSuchElementException("legal hold " + holdId + " not found");
            }
            return hold.release(releasedBy, now);
        });
    }

    /**
     * Whether a subject is under an active legal hold.
     *
     * @param subjectKeyId subject key id
     * @return {@code true} if at least one hold is active
     */
    public boolean hasActiveLegalHold(String subjectKeyId) {
        return db.read(em -> activeHold(em, subjectKeyId));
    }

    /**
     * Legal holds of a subject (active and released).
     *
     * @param subjectKeyId subject key id
     * @return holds, newest first
     */
    public List<EvidenceLegalHold> legalHoldsOf(String subjectKeyId) {
        return db.read(em -> em.createQuery("select h from EvidenceLegalHold h where h.subjectKeyId = :s "
                        + "order by h.placedAt desc", EvidenceLegalHold.class)
                .setParameter("s", subjectKeyId)
                .setMaxResults(PageRequest.MAX_LIMIT)
                .getResultList());
    }

    private static boolean activeHold(EntityManager em, String subjectKeyId) {
        return em.createQuery("select count(h) from EvidenceLegalHold h where h.subjectKeyId = :s "
                        + "and h.releasedAt is null", Long.class)
                .setParameter("s", subjectKeyId)
                .getSingleResult() > 0;
    }
}
