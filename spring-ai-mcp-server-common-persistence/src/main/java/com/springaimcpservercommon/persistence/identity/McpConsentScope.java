package com.springaimcpservercommon.persistence.identity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Scope a user consents to for an MCP client (matches {@code ck_mcp_client_consent_scope}). Mapped through
 * {@link DbConverter} because the database values contain dots.
 */
public enum McpConsentScope {
    /** Read tools. */
    READ("dai.mcp.read"),
    /** Tools that create write proposals for the user (never direct writes, ADR-0009). */
    PROPOSE("dai.mcp.propose"),
    /** Agents exposed as tools. */
    AGENTS("dai.mcp.agents");

    private final String value;

    McpConsentScope(String value) {
        this.value = value;
    }

    /**
     * The OAuth scope string (also the database value).
     *
     * @return scope value
     */
    public String value() {
        return value;
    }

    /**
     * Resolves a scope string.
     *
     * @param value OAuth scope string
     * @return the scope
     * @throws IllegalArgumentException for unknown values
     */
    public static McpConsentScope fromValue(String value) {
        for (McpConsentScope s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown MCP consent scope: " + value);
    }

    /** JPA converter between the enum and its database value. */
    @Converter
    public static final class DbConverter implements AttributeConverter<McpConsentScope, String> {

        /** Creates the converter. */
        public DbConverter() {
        }

        @Override
        public String convertToDatabaseColumn(McpConsentScope attribute) {
            return attribute.value();
        }

        @Override
        public McpConsentScope convertToEntityAttribute(String dbData) {
            return fromValue(dbData);
        }
    }
}
