package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.guard.PiiDetector;
import com.springaimcpservercommon.core.guard.PiiMatch;
import com.springaimcpservercommon.core.guard.PiiType;
import com.springaimcpservercommon.core.lint.SecretScanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageRedactorTest {

    private final MessageRedactor redactor = new MessageRedactor(new SecretScanner(), 200);

    @Test
    void plainTextIsStoredAsIs() {
        MessageRedactor.Redacted r = redactor.apply("Show me the open orders for ACME");

        assertThat(r.content()).isEqualTo("Show me the open orders for ACME");
        assertThat(r.redacted()).isFalse();
    }

    @Test
    void aMessageWithACredentialIsReplacedNotPartlyMasked() {
        MessageRedactor.Redacted r = redactor.apply("my key is sk-abcdefghijklmnopqrstuvwxyz123456, use it");

        assertThat(r.redacted()).isTrue();
        assertThat(r.content()).isEqualTo(MessageRedactor.REMOVED).doesNotContain("sk-abcdef");
    }

    @Test
    void passwordAssignmentsAndUrlCredentialsAreCaught() {
        assertThat(redactor.apply("password=hunter2hunter2").redacted()).isTrue();
        assertThat(redactor.apply("connect to postgres://admin:s3cret@db.internal/app").redacted()).isTrue();
    }

    @Test
    void longMessagesAreCutAtTheLimitWithAMarker() {
        MessageRedactor.Redacted r = redactor.apply("a".repeat(500));

        assertThat(r.content()).hasSize(200).endsWith(MessageRedactor.TRUNCATED);
        assertThat(r.redacted()).isFalse();
    }

    @Test
    void titleIsShortSingleLineAndAbsentForRemovedMessages() {
        MessageRedactor.Redacted long1 = redactor.apply("Line one\n\nline   two " + "x".repeat(150));

        assertThat(redactor.title(long1)).hasSizeLessThanOrEqualTo(80).doesNotContain("\n").startsWith("Line one line two");
        assertThat(redactor.title(new MessageRedactor.Redacted(MessageRedactor.REMOVED, true))).isNull();
        assertThat(redactor.title(new MessageRedactor.Redacted("   ", false))).isNull();
    }

    @Test
    void limitMustLeaveRoomForAMessage() {
        assertThatThrownBy(() -> new MessageRedactor(new SecretScanner(), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ConversationPii pii(DaiPiiProperties.Mode mode) {
        return ConversationPii.of(new DaiPiiProperties(mode, List.of(), Map.of("EMPLOYEE_ID", "E-\\d{6}")),
                List.of());
    }

    @Test
    void personalDataIsMaskedByTypeOrTheWholeMessageIsRemovedOrNothingHappens() {
        String text = "Contact jane@example.com about card 4111 1111 1111 1111 for E-123456 please";

        var masked = new MessageRedactor(new SecretScanner(), 300, pii(DaiPiiProperties.Mode.MASK)).apply(text);
        assertThat(masked.content())
                .isEqualTo("Contact [redacted email] about card [redacted credit card] for [redacted other] please");
        assertThat(masked.redacted()).isTrue();

        var removed = new MessageRedactor(new SecretScanner(), 300, pii(DaiPiiProperties.Mode.REMOVE)).apply(text);
        assertThat(removed.content()).isEqualTo(MessageRedactor.REMOVED_PII).doesNotContain("jane");

        var off = new MessageRedactor(new SecretScanner(), 300, pii(DaiPiiProperties.Mode.OFF)).apply(text);
        assertThat(off.content()).isEqualTo(text);
        assertThat(off.redacted()).isFalse();

        var clean = new MessageRedactor(new SecretScanner(), 300, pii(DaiPiiProperties.Mode.MASK))
                .apply("Show me the open orders");
        assertThat(clean.redacted()).isFalse();
    }

    @Test
    void onlyTheConfiguredBuiltInTypesAreMaskedButHostDetectorsAlwaysApply() {
        PiiDetector badges = t -> {
            int i = t.indexOf("BADGE-");
            return i < 0 ? List.of() : List.of(new PiiMatch(PiiType.OTHER, i, i + 9));
        };
        var onlyEmail = ConversationPii.of(new DaiPiiProperties(DaiPiiProperties.Mode.MASK, List.of(PiiType.EMAIL),
                Map.of()), List.of(badges));
        var r = new MessageRedactor(new SecretScanner(), 300, onlyEmail)
                .apply("jane@example.com from 192.168.1.20, BADGE-123, tel +44 20 7946 0958");
        assertThat(r.content()).isEqualTo("[redacted email] from 192.168.1.20, [redacted other], tel +44 20 7946 0958");
        // IP addresses are opt-in for storage by default
        assertThat(new MessageRedactor(new SecretScanner(), 300, pii(DaiPiiProperties.Mode.MASK))
                .apply("from 192.168.1.20").redacted()).isFalse();
    }

    @Test
    void aDetectorThatFailsNeverLetsThePersonalDataThrough() {
        PiiDetector broken = t -> {
            throw new IllegalStateException("model down");
        };
        for (DaiPiiProperties.Mode mode : List.of(DaiPiiProperties.Mode.MASK, DaiPiiProperties.Mode.REMOVE)) {
            var policy = ConversationPii.of(new DaiPiiProperties(mode, List.of(), Map.of()), List.of(broken));
            var r = new MessageRedactor(new SecretScanner(), 300, policy).apply("call me on +44 20 7946 0958");
            assertThat(r.content()).as(mode.name()).isEqualTo(MessageRedactor.REMOVED_PII);
            assertThat(r.redacted()).isTrue();
        }
    }

    @Test
    void anInvalidCustomPatternStopsStartup() {
        assertThatThrownBy(() -> ConversationPii.of(new DaiPiiProperties(DaiPiiProperties.Mode.MASK, List.of(),
                Map.of("BAD", "(")), List.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("BAD");
    }
}
