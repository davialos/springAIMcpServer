package com.springaimcpservercommon.persistence.identity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Hash algorithm of a stored API key secret (matches {@code ck_api_key_hash_algorithm}). The database values are
 * lowercase with dashes, so the enum is mapped through {@link DbConverter}.
 */
public enum ApiKeyHashAlgorithm {
    /** HMAC-SHA-256 with a server pepper (default: keys are 256-bit random secrets). */
    HMAC_SHA256("hmac-sha256"),
    /** Argon2id. */
    ARGON2ID("argon2id"),
    /** BCrypt. */
    BCRYPT("bcrypt"),
    /** PBKDF2 with HMAC-SHA-256. */
    PBKDF2_SHA256("pbkdf2-sha256");

    private final String dbValue;

    ApiKeyHashAlgorithm(String dbValue) {
        this.dbValue = dbValue;
    }

    /**
     * The value stored in {@code dai_api_key.hash_algorithm}.
     *
     * @return database value
     */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolves a database value.
     *
     * @param dbValue stored value
     * @return the algorithm
     * @throws IllegalArgumentException for unknown values
     */
    public static ApiKeyHashAlgorithm fromDbValue(String dbValue) {
        for (ApiKeyHashAlgorithm a : values()) {
            if (a.dbValue.equals(dbValue)) {
                return a;
            }
        }
        throw new IllegalArgumentException("unknown API key hash algorithm: " + dbValue);
    }

    /** JPA converter between the enum and its database value. */
    @Converter
    public static final class DbConverter implements AttributeConverter<ApiKeyHashAlgorithm, String> {

        /** Creates the converter. */
        public DbConverter() {
        }

        @Override
        public String convertToDatabaseColumn(ApiKeyHashAlgorithm attribute) {
            return attribute.dbValue();
        }

        @Override
        public ApiKeyHashAlgorithm convertToEntityAttribute(String dbData) {
            return fromDbValue(dbData);
        }
    }
}
