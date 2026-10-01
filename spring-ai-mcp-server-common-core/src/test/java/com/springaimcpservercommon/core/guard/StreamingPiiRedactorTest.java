package com.springaimcpservercommon.core.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingPiiRedactorTest {

    private static final String ANSWER = "Here is the customer you asked about. Their e-mail is jane.doe@example.com and "
            + "their phone is +1 415 555 2671. The card on file is 4111 1111 1111 1111, the IBAN is "
            + "DE89 3704 0044 0532 0130 00. " + "Order history follows. ".repeat(12)
            + "Escalations go to ops-team@example.org.";

    private final PiiRedactor redactor = PiiRedactor.defaults();

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 7, 11, 16, 64, 200, 5000})
    void chunkedOutputEqualsWholeRedactionAndNeverLeaksAValue(int chunkSize) {
        StreamingPiiRedactor stream = new StreamingPiiRedactor(redactor);
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < ANSWER.length(); i += chunkSize) {
            String out = stream.push(ANSWER.substring(i, Math.min(ANSWER.length(), i + chunkSize)));
            sent.append(out);
            // nothing released so far may contain the start of a value that is later redacted
            assertThat(sent.toString()).doesNotContain("jane.doe", "4111 1111", "DE89", "415 555", "ops-team");
        }
        sent.append(stream.flush());

        assertThat(sent.toString()).isEqualTo(redactor.redact(ANSWER).text());
        assertThat(stream.counts()).containsEntry(PiiType.EMAIL, 2).containsEntry(PiiType.PHONE, 1)
                .containsEntry(PiiType.CREDIT_CARD, 1).containsEntry(PiiType.IBAN, 1);
    }

    @Test
    void shortAnswersAreReleasedOnFlush() {
        StreamingPiiRedactor stream = new StreamingPiiRedactor(redactor);

        assertThat(stream.push("Hello ")).isEmpty();
        assertThat(stream.push("a@b.io")).isEmpty();
        assertThat(stream.flush()).isEqualTo("Hello [redacted email]");
    }

    @Test
    void aLongUnbrokenRunIsReleasedToBoundMemory() {
        StreamingPiiRedactor stream = new StreamingPiiRedactor(redactor);
        String run = "x".repeat(StreamingPiiRedactor.MAX_PENDING + 10);

        String out = stream.push(run);

        assertThat(out).hasSize(run.length() - StreamingPiiRedactor.HOLD_BACK);
        assertThat(out + stream.flush()).isEqualTo(run);
    }

    @Test
    void aFailingDetectorWithholdsTheRestOfTheStream() {
        PiiRedactor failing = new PiiRedactor(List.<PiiDetector>of(text -> {
            throw new IllegalStateException("boom");
        }));
        StreamingPiiRedactor stream = new StreamingPiiRedactor(failing);

        String out = stream.push("y ".repeat(200));

        assertThat(out).contains(PiiRedactor.WITHHELD);
        assertThat(stream.withheld()).isTrue();
        assertThat(stream.push("more text")).isEmpty();
        assertThat(stream.flush()).isEmpty();
    }
}
