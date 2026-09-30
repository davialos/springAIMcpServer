package com.springaimcpservercommon.core.guard;

import java.util.Locale;

/** Kind of personal or secret data found in text (F-76). */
public enum PiiType {

    /** E-mail address. */
    EMAIL,
    /** Telephone number (international or North American form). */
    PHONE,
    /** Payment card number (Luhn-valid, 13–19 digits). */
    CREDIT_CARD,
    /** International bank account number (ISO 13616 checksum-valid). */
    IBAN,
    /** Government identifier such as a US social security number. */
    NATIONAL_ID,
    /** IPv4 or IPv6 address. */
    IP_ADDRESS,
    /** Credential: API key, token, private key, password assignment (see {@code SecretScanner}). */
    CREDENTIAL,
    /** Anything a host-supplied {@link PiiDetector} reports that has no built-in type. */
    OTHER;

    /**
     * The placeholder that replaces a value of this type, e.g. {@code [redacted email]}.
     *
     * @return the placeholder text
     */
    public String placeholder() {
        return "[redacted " + name().toLowerCase(Locale.ROOT).replace('_', ' ') + "]";
    }
}
