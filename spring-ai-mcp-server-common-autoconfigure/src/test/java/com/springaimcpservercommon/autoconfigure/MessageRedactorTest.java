package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.lint.SecretScanner;
import org.junit.jupiter.api.Test;

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

    @Test
    void personalDataIsMaskedByLabelOrTheWholeMessageIsRemovedOrNothingHappens() {
        var detector = com.springaimcpservercommon.core.lint.RegexPiiDetector.defaults();
        String text = "Contact jane@example.com about card 4111 1111 1111 1111 please";

        var masked = new MessageRedactor(new SecretScanner(), 300, detector, DaiPiiProperties.Mode.MASK).apply(text);
        assertThat(masked.content()).isEqualTo("Contact [EMAIL] about card [CREDIT_CARD] please");
        assertThat(masked.redacted()).isTrue();

        var removed = new MessageRedactor(new SecretScanner(), 300, detector, DaiPiiProperties.Mode.REMOVE).apply(text);
        assertThat(removed.content()).isEqualTo(MessageRedactor.REMOVED_PII).doesNotContain("jane");

        var off = new MessageRedactor(new SecretScanner(), 300, detector, DaiPiiProperties.Mode.OFF).apply(text);
        assertThat(off.content()).isEqualTo(text);
        assertThat(off.redacted()).isFalse();

        var clean = new MessageRedactor(new SecretScanner(), 300, detector, DaiPiiProperties.Mode.MASK)
                .apply("Show me the open orders");
        assertThat(clean.redacted()).isFalse();
    }

    @Test
    void aDetectorThatFailsNeverLetsThePersonalDataThrough() {
        var broken = new MessageRedactor(new SecretScanner(), 300, t -> {
            throw new IllegalStateException("model down");
        }, DaiPiiProperties.Mode.MASK).apply("call me on +44 20 7946 0958");
        assertThat(broken.content()).isEqualTo(MessageRedactor.REMOVED_PII);
        assertThat(broken.redacted()).isTrue();
    }
}
