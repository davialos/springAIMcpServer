package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.core.hash.Sha256;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditCanonicalFormTest {

    private static final UUID ID = UUID.fromString("0192a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
    private static final UUID ACTOR = UUID.fromString("0192a1b2-0000-7000-8000-00000000000a");
    private static final UUID WS = UUID.fromString("0192a1b2-0000-7000-8000-0000000000ff");
    private static final Instant AT = Instant.parse("2026-09-28T10:15:30.123456789Z");

    private static AuditEventDraft draft(String details) {
        return new AuditEventDraft(AuditCategory.INVOCATION, "TOOL_INVOKED", AuditPlane.AGENT, ACTOR,
                AuditActorType.USER, null, WS, "tool", "find_orders", AuditDecision.PERMIT, null, "trace-1", null,
                null, null, null, details, null);
    }

    private static AuditEvent event(String details, String prev) {
        return AuditEvent.chained(draft(details), ID, AT, WS.toString(), 1, "orders-dev", prev);
    }

    @Test
    void canonicalFormFollowsTheDocumentedLayout() {
        AuditEvent e = event("{\"rows\":3,\"entity\":\"Order\"}", AuditCanonicalForm.genesis(WS.toString()));

        String expected = "[\"dai-audit-v1\",\"" + ID + "\",\"2026-09-28T10:15:30.123456Z\",\"" + WS + "\",1,"
                + "\"INVOCATION\",\"TOOL_INVOKED\",\"AGENT\",\"" + ACTOR + "\",\"USER\",null,\"" + WS + "\","
                + "\"tool\",\"find_orders\",\"PERMIT\",null,\"orders-dev\",\"trace-1\",null,null,null,null,"
                + "{\"entity\":\"Order\",\"rows\":3},\"" + Sha256.of(WS.toString()) + "\"]";
        assertThat(AuditCanonicalForm.canonical(e)).isEqualTo(expected);
        assertThat(e.getHash()).isEqualTo(Sha256.of(expected));
    }

    @Test
    void timestampIsTruncatedToMicrosecondsWithSixDigits() {
        AuditEvent e = event(null, AuditCanonicalForm.genesis(WS.toString()));

        assertThat(e.getOccurredAt()).isEqualTo(Instant.parse("2026-09-28T10:15:30.123456Z"));
        AuditEvent whole = AuditEvent.chained(draft(null), ID, Instant.parse("2026-09-28T10:15:30Z"), WS.toString(), 1,
                "orders-dev", AuditCanonicalForm.genesis(WS.toString()));
        assertThat(AuditCanonicalForm.canonical(whole)).contains("\"2026-09-28T10:15:30.000000Z\"");
    }

    @Test
    void detailsFormattingDoesNotChangeTheHash() {
        String prev = AuditCanonicalForm.genesis(WS.toString());
        AuditEvent compact = event("{\"a\":1.50,\"b\":[true,null]}", prev);
        AuditEvent jsonbStyle = event("{\"b\": [true, null], \"a\": 1.5}", prev);

        assertThat(jsonbStyle.getHash()).isEqualTo(compact.getHash());
    }

    @Test
    void everyFieldAndThePreviousHashAffectTheHash() {
        String prev = AuditCanonicalForm.genesis(WS.toString());
        AuditEvent base = event(null, prev);

        assertThat(event(null, Sha256.of("other")).getHash()).isNotEqualTo(base.getHash());
        assertThat(event("{}", prev).getHash()).isNotEqualTo(base.getHash());
        AuditEvent otherSeq = AuditEvent.chained(draft(null), ID, AT, WS.toString(), 2, "orders-dev", prev);
        assertThat(otherSeq.getHash()).isNotEqualTo(base.getHash());
        AuditEvent otherEnv = AuditEvent.chained(draft(null), ID, AT, WS.toString(), 1, "orders-prod", prev);
        assertThat(otherEnv.getHash()).isNotEqualTo(base.getHash());
    }

    @Test
    void textNullIsDistinctFromAbsentValue() {
        String prev = AuditCanonicalForm.genesis(WS.toString());
        AuditEventDraft withReason = new AuditEventDraft(AuditCategory.SECURITY, "AUTHZ_DENIED", AuditPlane.DATA,
                ACTOR, AuditActorType.USER, null, WS, null, null, AuditDecision.DENY, "null", null, null, null, null,
                null, null, null);
        AuditEvent e = AuditEvent.chained(withReason, ID, AT, WS.toString(), 1, "orders-dev", prev);

        assertThat(AuditCanonicalForm.canonical(e)).contains("\"DENY\",\"null\",").contains(",null,null,null,null,null,");
    }

    @Test
    void genesisIsTheHashOfTheChainId() {
        assertThat(AuditCanonicalForm.genesis("system")).isEqualTo(Sha256.of("system"));
    }
}
