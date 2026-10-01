package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Infers a field's {@link FieldKind} from its declared format, enum values, name and type — in that order, so
 * an explicit {@code format: email} beats a name heuristic.
 */
public final class FieldKindClassifier {

    private FieldKindClassifier() {
    }

    /**
     * Classifies a scalar field.
     *
     * @param name   field or parameter name
     * @param schema its schema
     * @return the kind
     */
    public static FieldKind classify(String name, ScalarSchema schema) {
        if (!schema.enumValues().isEmpty()) {
            return FieldKind.ENUM;
        }
        if (schema.type() == ScalarType.BOOLEAN) {
            return FieldKind.BOOLEAN;
        }
        String n = Names.normalize(name);
        List<String> words = Arrays.asList(Names.snakeCase(name).split("[^a-z0-9]+"));
        String last = words.isEmpty() ? n : words.getLast();
        boolean string = schema.type() == ScalarType.STRING;
        boolean numeric = schema.type() == ScalarType.INTEGER || schema.type() == ScalarType.NUMBER;

        FieldKind byFormat = byFormat(schema.format(), n);
        if (byFormat != null) {
            return byFormat;
        }
        if (any(n, "requestid", "correlationid", "traceid", "idempotency", "messageid", "transactionid")
                && string) {
            return FieldKind.UUID;
        }
        if (n.equals("id") || last.equals("id") || n.endsWith("uuid") || n.endsWith("guid")) {
            return FieldKind.ID;
        }
        if (string) {
            FieldKind s = stringByName(n, words, last);
            if (s != null) {
                return s;
            }
        }
        if (numeric) {
            FieldKind num = numberByName(n, words, last, schema.type() == ScalarType.INTEGER);
            if (num != null) {
                return num;
            }
            return schema.type() == ScalarType.INTEGER ? FieldKind.INTEGER : FieldKind.NUMBER;
        }
        return FieldKind.TEXT;
    }

    private static @Nullable FieldKind byFormat(@Nullable String format, String n) {
        if (format == null) {
            return null;
        }
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "email", "idn-email" -> FieldKind.EMAIL;
            case "uuid" -> FieldKind.UUID;
            case "date" -> n.contains("birth") || n.equals("dob") ? FieldKind.BIRTH_DATE : FieldKind.DATE;
            case "date-time" -> FieldKind.DATE_TIME;
            case "time" -> FieldKind.TIME;
            case "uri", "url", "uri-reference", "iri" -> FieldKind.URL;
            case "ipv4", "ipv6" -> FieldKind.IP;
            case "password" -> FieldKind.PASSWORD;
            case "currency" -> FieldKind.CURRENCY;
            case "locale" -> FieldKind.LANGUAGE;
            case "timezone" -> FieldKind.TIMEZONE;
            default -> null;
        };
    }

    private static boolean any(String n, String... needles) {
        for (String needle : needles) {
            if (n.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable FieldKind stringByName(String n, List<String> words, String last) {
        Set<String> w = Set.copyOf(words);
        if (any(n, "email", "mail")) {
            return FieldKind.EMAIL;
        }
        if (any(n, "password", "passwd", "secret") || w.contains("pwd")) {
            return FieldKind.PASSWORD;
        }
        if (any(n, "username", "login", "nickname", "handle", "screenname")) {
            return FieldKind.USERNAME;
        }
        if (any(n, "firstname", "givenname", "forename")) {
            return FieldKind.FIRST_NAME;
        }
        if (any(n, "lastname", "surname", "familyname")) {
            return FieldKind.LAST_NAME;
        }
        if (any(n, "fullname", "displayname", "customername", "contactname", "ownername", "authorname",
                "personname", "employeename") || n.equals("author") || n.equals("owner")) {
            return FieldKind.FULL_NAME;
        }
        if (any(n, "phone", "mobile", "msisdn", "fax") || w.contains("tel") || n.equals("telephone")) {
            return FieldKind.PHONE;
        }
        if (any(n, "street", "addressline", "address1", "address2", "line1", "line2")) {
            return FieldKind.STREET;
        }
        if (any(n, "address")) {
            return FieldKind.ADDRESS;
        }
        if (any(n, "city", "town")) {
            return FieldKind.CITY;
        }
        if (any(n, "countrycode", "isocode") || n.equals("iso2") || n.equals("iso3")) {
            return FieldKind.COUNTRY_CODE;
        }
        if (any(n, "country", "nationality")) {
            return FieldKind.COUNTRY;
        }
        if (any(n, "zip", "postal", "postcode", "pincode")) {
            return FieldKind.POSTAL_CODE;
        }
        if (any(n, "province", "region") || n.equals("state") || last.equals("state") && !n.contains("status")) {
            return FieldKind.STATE;
        }
        if (any(n, "company", "organization", "organisation", "employer", "vendor", "supplier", "manufacturer")) {
            return FieldKind.COMPANY;
        }
        if (any(n, "jobtitle", "occupation", "designation", "position")) {
            return FieldKind.JOB_TITLE;
        }
        if (any(n, "url", "link", "website", "homepage", "href", "avatar", "image", "photo", "picture", "uri")) {
            return FieldKind.URL;
        }
        if (w.contains("ip") || any(n, "ipaddress")) {
            return FieldKind.IP;
        }
        if (any(n, "birth") || n.equals("dob")) {
            return FieldKind.BIRTH_DATE;
        }
        if (any(n, "currency")) {
            return FieldKind.CURRENCY;
        }
        if (any(n, "timezone") || n.equals("tz")) {
            return FieldKind.TIMEZONE;
        }
        if (any(n, "language", "locale") || n.equals("lang")) {
            return FieldKind.LANGUAGE;
        }
        if (any(n, "creditcard", "cardnumber") || n.equals("card")) {
            return FieldKind.CREDIT_CARD;
        }
        if (any(n, "token", "apikey")) {
            return FieldKind.TOKEN;
        }
        if (any(n, "slug")) {
            return FieldKind.SLUG;
        }
        if (any(n, "version")) {
            return FieldKind.VERSION;
        }
        if (any(n, "color", "colour")) {
            return FieldKind.COLOR;
        }
        if (any(n, "gender") || n.equals("sex")) {
            return FieldKind.GENDER;
        }
        if (any(n, "status")) {
            return FieldKind.STATUS;
        }
        if (n.equals("sort") || any(n, "sortby", "orderby")) {
            return FieldKind.SORT;
        }
        if (n.equals("q") || any(n, "query", "search", "keyword", "term", "filter")) {
            return FieldKind.SEARCH;
        }
        if (any(n, "title", "subject", "headline", "heading")) {
            return FieldKind.TITLE;
        }
        if (any(n, "description", "summary", "notes", "note", "comment", "bio", "message", "content", "body",
                "text", "remark", "details", "instructions") || n.equals("desc")) {
            return FieldKind.DESCRIPTION;
        }
        if (any(n, "sku", "code", "reference", "number", "barcode", "isbn", "serial") || n.equals("ref")) {
            return FieldKind.CODE;
        }
        if (w.contains("date") || last.equals("on") || last.equals("day")) {
            return FieldKind.DATE;
        }
        if (last.equals("at") || last.equals("time") || last.equals("timestamp") || last.equals("datetime")) {
            return FieldKind.DATE_TIME;
        }
        if (any(n, "price", "amount")) {
            return FieldKind.PRICE;
        }
        if (n.equals("name") || last.equals("name") || last.equals("label")) {
            return FieldKind.NAME;
        }
        return null;
    }

    private static @Nullable FieldKind numberByName(String n, List<String> words, String last, boolean integer) {
        Set<String> w = Set.copyOf(words);
        if (n.equals("page") || n.equals("pagenumber") || n.equals("pageno") || n.equals("offset")) {
            return FieldKind.PAGE;
        }
        if (n.equals("size") || n.equals("limit") || n.equals("pagesize") || n.equals("perpage")) {
            return FieldKind.PAGE_SIZE;
        }
        if (w.contains("age")) {
            return FieldKind.AGE;
        }
        if (any(n, "price", "amount", "cost", "total", "salary", "balance", "fee", "subtotal", "tax", "revenue",
                "budget", "income", "payment")) {
            return FieldKind.PRICE;
        }
        if (any(n, "percent", "discount", "ratio")) {
            return FieldKind.PERCENTAGE;
        }
        if (any(n, "quantity", "stock", "units", "inventory") || w.contains("qty") || w.contains("count")) {
            return FieldKind.QUANTITY;
        }
        if (any(n, "latitude") || n.equals("lat")) {
            return FieldKind.LATITUDE;
        }
        if (any(n, "longitude") || n.equals("lng") || n.equals("lon") || n.equals("long")) {
            return FieldKind.LONGITUDE;
        }
        if (any(n, "rating", "score", "stars")) {
            return FieldKind.RATING;
        }
        if (integer && (last.equals("year") || any(n, "version"))) {
            return FieldKind.INTEGER;
        }
        return null;
    }
}
