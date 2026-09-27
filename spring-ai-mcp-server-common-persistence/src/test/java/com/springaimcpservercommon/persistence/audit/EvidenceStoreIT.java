package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.persistence.unit.DaiPersistenceUnit;
import com.springaimcpservercommon.persistence.unit.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Evidence mode against PostgreSQL: encryption at rest, crypto-shredding, legal holds. */
class EvidenceStoreIT {

    private static DaiPersistenceUnit unit;
    private static EvidenceStore evidence;
    private static AuditTrail trail;
    private static UUID auditor;

    @BeforeAll
    static void start() {
        unit = PostgresTestSupport.startFreshUnit();
        InMemoryDataKeyProvider keys = new InMemoryDataKeyProvider();
        evidence = new EvidenceStore(unit, new AesGcmEvidenceCipher(keys), keys, Clock.systemUTC());
        trail = new AuditTrail(unit, "it-env", Clock.systemUTC());
        auditor = PostgresTestSupport.principal(unit.schema());
    }

    @AfterAll
    static void stop() {
        unit.close();
    }

    private UUID storePrompt(String subject, String text) {
        AppendedAuditEvent event = trail.append(AuditEventDraft.system("AGENT_TURN", null, null));
        return evidence.write(new NewEvidence(event.id(), event.occurredAt(), subject, null, EvidenceContentType.PROMPT,
                text.getBytes(StandardCharsets.UTF_8), Instant.now().plus(Duration.ofDays(400))));
    }

    @Test
    void storesCiphertextOnlyAndDecryptsForReaders() {
        UUID id = storePrompt("subject-a", "patient 4711 has diabetes");

        byte[] stored = PostgresTestSupport.jdbc().queryForObject(
                "SELECT ciphertext FROM " + unit.schema() + ".dai_audit_evidence WHERE id = ?", byte[].class, id);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("diabetes");
        assertThat(new String(evidence.read(id), StandardCharsets.UTF_8)).isEqualTo("patient 4711 has diabetes");
    }

    @Test
    void shreddingMakesEvidenceUnreadableButKeepsTheAuditChainValid() {
        UUID id = storePrompt("subject-b", "secret");

        assertThat(evidence.shred("subject-b", auditor)).isTrue();

        assertThatThrownBy(() -> evidence.read(id)).isInstanceOf(EvidenceShreddedException.class);
        assertThatThrownBy(() -> storePrompt("subject-b", "more")).isInstanceOf(EvidenceShreddedException.class);
        EvidenceSubjectKey key = evidence.findSubjectKey("subject-b").orElseThrow();
        assertThat(key.isShredded()).isTrue();
        assertThat(key.getShreddedBy()).isEqualTo(auditor);
        long head = trail.head(AuditTrail.SYSTEM_CHAIN).orElseThrow().getLastSeq();
        assertThat(trail.verify(AuditTrail.SYSTEM_CHAIN, 1, head).intact()).isTrue();
    }

    @Test
    void legalHoldBlocksShreddingUntilReleased() {
        UUID id = storePrompt("subject-c", "keep me");
        EvidenceLegalHold hold = evidence.placeLegalHold("subject-c", "CASE-2026-17", auditor);

        assertThat(evidence.hasActiveLegalHold("subject-c")).isTrue();
        assertThatThrownBy(() -> evidence.shred("subject-c", auditor)).isInstanceOf(LegalHoldActiveException.class);
        assertThat(evidence.read(id)).isNotEmpty();

        assertThat(evidence.releaseLegalHold(hold.getId(), auditor)).isTrue();
        assertThat(evidence.shred("subject-c", auditor)).isTrue();
        assertThat(evidence.legalHoldsOf("subject-c")).singleElement()
                .satisfies(h -> assertThat(h.isActive()).isFalse());
    }
}
