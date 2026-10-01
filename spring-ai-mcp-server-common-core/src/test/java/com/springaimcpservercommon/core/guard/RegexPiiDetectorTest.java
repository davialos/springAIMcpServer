package com.springaimcpservercommon.core.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detection cases of the built-in detector, including the look-alikes it must leave alone. Ported from the former
 * storage-only {@code core.lint.RegexPiiDetector} when stored-conversation masking moved onto this detector.
 */
class RegexPiiDetectorTest {

    private final RegexPiiDetector detector = new RegexPiiDetector();

    private List<String> found(String text) {
        return detector.detect(text).stream()
                .sorted(java.util.Comparator.comparingInt(PiiMatch::start))
                .map(m -> m.type() + ":" + text.substring(m.start(), m.end())).toList();
    }

    @Test
    void findsEmailPhoneCardIbanAndNationalIdWithTheirPositions() {
        String text = "Mail jane.doe+x@example.co.uk or call +44 20 7946 0958; card 4111 1111 1111 1111, "
                + "IBAN DE89 3704 0044 0532 0130 00, ssn 123-45-6789.";
        assertThat(found(text)).containsExactly("EMAIL:jane.doe+x@example.co.uk", "PHONE:+44 20 7946 0958",
                "CREDIT_CARD:4111 1111 1111 1111", "IBAN:DE89 3704 0044 0532 0130 00", "NATIONAL_ID:123-45-6789");
    }

    @Test
    void nationalPhoneNumbersWithATrunkPrefixAreFound() {
        assertThat(found("tel 020 7946 0958 today")).containsExactly("PHONE:020 7946 0958");
        assertThat(found("call 0711-123456")).containsExactly("PHONE:0711-123456");
    }

    @Test
    void numbersThatOnlyLookLikePersonalDataAreLeftAlone() {
        for (String text : List.of("Order 1234567890123 shipped", "Invoice 2026-09-30 total 12,345.67",
                "card 4111 1111 1111 1112", "the year 2026 and 15 items", "ssn 000-12-3456", "order o-12345678",
                "version 0.9.12", "build 01-02", "ref 0042")) {
            assertThat(found(text)).as(text).isEmpty();
        }
    }

    @Test
    void ipAddressesAreReportedAsTheirOwnType() {
        assertThat(found("from 192.168.10.20")).containsExactly("IP_ADDRESS:192.168.10.20");
    }
}
