package com.springaimcpservercommon.core.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private final PiiRedactor redactor = PiiRedactor.defaults();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Contact jane.doe+billing@example.co.uk today|Contact [redacted email] today|EMAIL",
            "Call +44 20 7946 0958 now|Call [redacted phone] now|PHONE",
            "Call (555) 123-4567 now|Call [redacted phone] now|PHONE",
            "Call 555-123-4567.|Call [redacted phone].|PHONE",
            "Card 4111 1111 1111 1111 expires|Card [redacted credit card] expires|CREDIT_CARD",
            "Card 4111-1111-1111-1111|Card [redacted credit card]|CREDIT_CARD",
            "IBAN DE89 3704 0044 0532 0130 00 AND more|IBAN [redacted iban] AND more|IBAN",
            "IBAN GB82WEST12345698765432.|IBAN [redacted iban].|IBAN",
            "SSN 123-45-6789 on file|SSN [redacted national id] on file|NATIONAL_ID",
            "Seen from 192.168.10.23 at noon|Seen from [redacted ip address] at noon|IP_ADDRESS",
            "Seen from 2001:0db8:85a3:0000:0000:8a2e:0370:7334|Seen from [redacted ip address]|IP_ADDRESS",
            "Use key sk-abcdefghijklmnopqrstuvwxyz123456|Use key [redacted credential]|CREDENTIAL",
            "password=hunter2secret please|[redacted credential] please|CREDENTIAL"
    })
    void redactsEachBuiltInType(String input, String expected, PiiType type) {
        PiiRedactor.Redaction r = redactor.redact(input);

        assertThat(r.text()).isEqualTo(expected);
        assertThat(r.counts()).containsEntry(type, 1);
        assertThat(r.changed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Order 12345 totals 99.50 EUR and shipped on 2026-09-30",
            "Upgrade to version 2.0.1 of the library",
            "Card 1234 5678 9012 3456 fails the Luhn check",
            "Invoice INV-2026-000123 is overdue by 14 days",
            "The meeting is at 10:30 in room 4",
            "Reference DE00 0000 0000 0000 0000 00 is not an IBAN",
            "Customer 4711 bought 3 items for 1299 cents"
    })
    void leavesBusinessDataAlone(String input) {
        PiiRedactor.Redaction r = redactor.redact(input);

        assertThat(r.text()).isEqualTo(input);
        assertThat(r.changed()).isFalse();
    }

    @Test
    void countsSeveralValuesAndKeepsTheRest() {
        PiiRedactor.Redaction r = redactor.redact("a@x.io, b@y.io and +1 415 555 2671");

        assertThat(r.text()).isEqualTo("[redacted email], [redacted email] and [redacted phone]");
        assertThat(r.counts()).containsEntry(PiiType.EMAIL, 2).containsEntry(PiiType.PHONE, 1);
    }

    @Test
    void overlappingDetectionsAreMergedIntoOneRedaction() {
        PiiDetector wide = text -> text.contains("secret-ref")
                ? List.of(new PiiMatch(PiiType.OTHER, text.indexOf("secret-ref") - 3, text.indexOf("secret-ref") + 10))
                : List.of();
        PiiDetector narrow = text -> text.contains("secret-ref")
                ? List.of(new PiiMatch(PiiType.OTHER, text.indexOf("secret-ref") + 2, text.indexOf("secret-ref") + 12))
                : List.of();
        PiiRedactor custom = new PiiRedactor(List.<PiiDetector>of(wide, narrow));

        assertThat(custom.redact("ab xyzsecret-ref12 tail").text()).isEqualTo("ab [redacted other] tail");
    }

    @Test
    void hostDetectorsAddFormats() {
        PiiDetector employeeIds = text -> {
            var m = java.util.regex.Pattern.compile("EMP-\\d{6}").matcher(text);
            List<PiiMatch> out = new java.util.ArrayList<>();
            while (m.find()) {
                out.add(new PiiMatch(PiiType.OTHER, m.start(), m.end()));
            }
            return out;
        };
        PiiRedactor custom = new PiiRedactor(List.<PiiDetector>of(new RegexPiiDetector(), employeeIds));

        assertThat(custom.redact("EMP-123456 wrote to a@b.io").text())
                .isEqualTo("[redacted other] wrote to [redacted email]");
    }

    @Test
    void aFailingDetectorWithholdsTheWholeText() {
        PiiRedactor failing = new PiiRedactor(List.<PiiDetector>of(new RegexPiiDetector(), text -> {
            throw new IllegalStateException("boom");
        }));

        PiiRedactor.Redaction r = failing.redact("anything at all");

        assertThat(r.withheld()).isTrue();
        assertThat(r.text()).isEqualTo(PiiRedactor.WITHHELD);
        assertThat(failing.containsPii("anything")).isTrue();
    }

    @Test
    void luhnAndIbanChecksums() {
        assertThat(RegexPiiDetector.luhnValid("4111111111111111")).isTrue();
        assertThat(RegexPiiDetector.luhnValid("4111111111111112")).isFalse();
        assertThat(RegexPiiDetector.ibanValid("DE89370400440532013000")).isTrue();
        assertThat(RegexPiiDetector.ibanValid("DE89370400440532013001")).isFalse();
    }
}
