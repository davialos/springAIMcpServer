package com.springaimcpservercommon.persistence.usage;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validation of ISO-4217 alphabetic currency codes as stored in {@code char(3)} columns. */
final class Currencies {

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private Currencies() {
    }

    static String require(String currency) {
        Objects.requireNonNull(currency, "currency");
        if (!CODE.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code like EUR: " + currency);
        }
        return currency;
    }
}
