package com.springaimcpservercommon.persistence.unit;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;

/**
 * Hibernate JSON {@link FormatMapper} for the {@code dynamic_ai} unit that passes JSON text through unchanged.
 *
 * <p>Every {@code jsonb} column of the unit is mapped as {@code @JdbcTypeCode(SqlTypes.JSON) String}: the stores
 * produce and consume JSON text themselves (canonicalised where it is hashed, see {@link com.springaimcpservercommon.persistence.support.CanonicalJson}). Hibernate
 * still needs a JSON format mapper to bind such attributes; its automatic detection only knows Jackson 2 (Jackson 3,
 * which Spring Boot 4 uses, is supported from Hibernate 7.3 on) and would otherwise make the unit depend on whatever
 * JSON library the host happens to have. Configuring this mapper explicitly
 * ({@code hibernate.type.json_format_mapper}) keeps the unit deterministic and isolated (LLD-12 §4).
 *
 * <p>Only {@code String} (and {@code Object}) attributes are supported; mapping any other Java type to JSON in this
 * unit is a programming error and fails loudly.
 */
public final class PassThroughJsonFormatMapper implements FormatMapper {

    /** Shared instance (stateless). */
    public static final PassThroughJsonFormatMapper INSTANCE = new PassThroughJsonFormatMapper();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        Class<T> type = javaType.getJavaTypeClass();
        if (type == String.class || type == Object.class) {
            return (T) charSequence.toString();
        }
        throw unsupported(type);
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        if (value instanceof String text) {
            return text;
        }
        throw unsupported(javaType.getJavaTypeClass());
    }

    private static UnsupportedOperationException unsupported(Class<?> type) {
        return new UnsupportedOperationException("dynamic_ai persistence unit maps JSON columns as String only, not "
                + type.getName() + " (see PassThroughJsonFormatMapper)");
    }
}
