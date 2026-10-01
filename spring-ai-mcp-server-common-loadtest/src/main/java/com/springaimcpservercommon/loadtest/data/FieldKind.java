package com.springaimcpservercommon.loadtest.data;

import java.util.Locale;

/**
 * Semantic kind of a request field. Discovery decides the kind (Java, {@link FieldKindClassifier}); the
 * generated suite's {@code lib/dummy.js} owns how a realistic value of each kind is produced.
 */
public enum FieldKind {
    ID, UUID, EMAIL, USERNAME, PASSWORD, FIRST_NAME, LAST_NAME, FULL_NAME, NAME, PHONE,
    STREET, ADDRESS, CITY, STATE, COUNTRY, COUNTRY_CODE, POSTAL_CODE, COMPANY, JOB_TITLE,
    URL, IP, DATE, DATE_TIME, TIME, BIRTH_DATE, AGE, PRICE, CURRENCY, QUANTITY, PERCENTAGE,
    LATITUDE, LONGITUDE, TITLE, DESCRIPTION, CODE, STATUS, COLOR, GENDER, LANGUAGE, TIMEZONE,
    CREDIT_CARD, TOKEN, SLUG, VERSION, RATING, PAGE, PAGE_SIZE, SORT, SEARCH,
    ENUM, BOOLEAN, TEXT, INTEGER, NUMBER;

    /**
     * Name of the generator in the suite's {@code lib/dummy.js}.
     *
     * @return lower camel case name, e.g. {@code firstName}
     */
    public String generator() {
        String[] parts = name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return out.toString();
    }

    /**
     * Whether values of this kind identify a row (so real values matter: random ones would mostly 404).
     *
     * @return {@code true} for {@link #ID} and {@link #UUID}
     */
    public boolean identifier() {
        return this == ID || this == UUID;
    }

    /**
     * Whether values of this kind are usually unique per row, so generated values get a per-request suffix.
     *
     * @return {@code true} for e-mail, username, code and slug
     */
    public boolean unique() {
        return this == EMAIL || this == USERNAME || this == CODE || this == SLUG;
    }
}
