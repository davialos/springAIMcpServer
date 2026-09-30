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
}
