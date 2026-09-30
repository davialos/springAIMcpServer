package com.springaimcpservercommon.core.lint;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegexPiiDetectorTest {

    private final RegexPiiDetector detector = RegexPiiDetector.defaults();

    private static List<String> labels(List<PiiDetector.Match> matches) {
        return matches.stream().map(PiiDetector.Match::label).toList();
    }

    @Test
    void findsEmailPhoneCardIbanAndSsnWithTheirPositions() {
        String text = "Mail jane.doe+x@example.co.uk or call +44 20 7946 0958; card 4111 1111 1111 1111, "
                + "IBAN DE89 3704 0044 0532 0130 00, ssn 123-45-6789.";
        var matches = detector.find(text);
        assertThat(labels(matches)).containsExactly("EMAIL", "PHONE", "CREDIT_CARD", "IBAN", "US_SSN");
        assertThat(text.substring(matches.get(0).start(), matches.get(0).end())).isEqualTo("jane.doe+x@example.co.uk");
        assertThat(text.substring(matches.get(2).start(), matches.get(2).end())).isEqualTo("4111 1111 1111 1111");
    }

    @Test
    void numbersThatOnlyLookLikePersonalDataAreLeftAlone() {
        for (String text : List.of("Order 1234567890123 shipped", "Invoice 2026-09-30 total 12,345.67",
                "card 4111 1111 1111 1112", "IBAN DE89 3704 0044 0532 0130 01", "version 1.2.3.4",
                "the year 2026 and 15 items", "ssn 000-12-3456", "order o-12345678")) {
            assertThat(detector.find(text)).as(text).isEmpty();
        }
    }

    @Test
    void anAcceptedSpanBlocksOverlappingOnesAndIpv4IsOptIn() {
        // a valid card number is not also reported as a phone number
        assertThat(labels(detector.find("pay with 4111111111111111 now"))).containsExactly("CREDIT_CARD");
        assertThat(detector.find("from 192.168.10.20")).isEmpty();
        var withIp = new RegexPiiDetector(EnumSet.of(RegexPiiDetector.Type.IPV4), Map.of());
        assertThat(labels(withIp.find("from 192.168.10.20"))).containsExactly("IPV4");
    }

    @Test
    void hostPatternsAreDetectedAndNeedAProperLabel() {
        var custom = new RegexPiiDetector(EnumSet.noneOf(RegexPiiDetector.Type.class),
                Map.of("EMPLOYEE_ID", Pattern.compile("E-\\d{6}")));
        assertThat(labels(custom.find("ask E-123456 about it"))).containsExactly("EMPLOYEE_ID");
        assertThat(custom.find("jane@example.com")).isEmpty();
        assertThatThrownBy(() -> new RegexPiiDetector(EnumSet.noneOf(RegexPiiDetector.Type.class),
                Map.of("bad label", Pattern.compile("x")))).isInstanceOf(IllegalArgumentException.class);
    }
}
