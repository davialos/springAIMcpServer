package com.springaimcpservercommon.core.guard;

import java.util.List;

/**
 * SPI: finds personal or secret data in text (LLD-06 §8, F-76).
 *
 * <p>The library ships {@link RegexPiiDetector} (e-mail, phone, payment card, IBAN, US SSN, IP address,
 * credentials). A host adds its own formats (employee numbers, national identifiers of its market, customer
 * references) by declaring a {@code PiiDetector} bean; every detector bean runs in addition to the built-in one.
 *
 * <p>Implementations must be thread-safe, side-effect free and fast (they run on every answer and, for streamed
 * answers, repeatedly over a small window). They must never log the text they inspect. A detector that throws makes
 * the redactor withhold the whole text (fail closed).
 */
@FunctionalInterface
public interface PiiDetector {

    /**
     * Finds personal data in a text.
     *
     * @param text text to inspect, never {@code null}
     * @return matches in any order; ranges may overlap (the redactor merges them)
     */
    List<PiiMatch> detect(String text);
}
