package com.springaimcpservercommon.loadtest.schema;

import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Quotes identifiers for the DDL a snapshot is rendered as: only when the name would not survive unquoted
 * (mixed case, special characters, reserved word), and always with embedded quotes doubled so a name from the
 * database can never break out of the statement.
 *
 * @param quote     the driver's identifier quote string, or empty when the database has none
 * @param upperCase whether unquoted identifiers fold to upper case (Oracle, H2) instead of lower case
 */
public record SqlNames(String quote, boolean upperCase) {

    private static final Pattern LOWER = Pattern.compile("[a-z_][a-z0-9_$]*");
    private static final Pattern UPPER = Pattern.compile("[A-Z_][A-Z0-9_$]*");

    /** SQL reserved words (PostgreSQL's reserved list, which is also reserved or risky on the other databases). */
    private static final Set<String> RESERVED = Set.of("all", "analyse", "analyze", "and", "any", "array", "as", "asc",
            "asymmetric", "both", "case", "cast", "check", "collate", "column", "constraint", "create", "current_date",
            "current_time", "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct", "do",
            "else", "end", "except", "false", "fetch", "for", "foreign", "from", "grant", "group", "having", "in",
            "initially", "intersect", "into", "lateral", "leading", "limit", "localtime", "localtimestamp", "not",
            "null", "offset", "on", "only", "or", "order", "placing", "primary", "references", "returning", "select",
            "session_user", "some", "symmetric", "table", "then", "to", "trailing", "true", "union", "unique", "user",
            "using", "variadic", "when", "where", "window", "with");

    /**
     * An identifier, quoted when needed.
     *
     * @param identifier a schema, table, column or constraint name
     * @return SQL text for it
     */
    public String id(String identifier) {
        if (quote.isEmpty()) {
            return identifier;
        }
        boolean simple = (upperCase ? UPPER : LOWER).matcher(identifier).matches()
                && !RESERVED.contains(identifier.toLowerCase(java.util.Locale.ROOT));
        return simple ? identifier : quote + identifier.replace(quote, quote + quote) + quote;
    }

    /**
     * A schema-qualified name.
     *
     * @param schema schema, or {@code null}
     * @param name   object name
     * @return {@code schema.name} or just {@code name}
     */
    public String qualified(@Nullable String schema, String name) {
        return schema == null || schema.isEmpty() ? id(name) : id(schema) + "." + id(name);
    }

    /**
     * A schema-qualified name.
     *
     * @param name the name
     * @return {@code schema.name} or just {@code name}
     */
    public String qualified(SchemaSnapshot.Name name) {
        return qualified(name.schema(), name.name());
    }

    /**
     * A comma-separated column list.
     *
     * @param columns column names
     * @return {@code a, b, c}
     */
    public String columns(java.util.List<String> columns) {
        return columns.stream().map(this::id).collect(java.util.stream.Collectors.joining(", "));
    }
}
