package com.springaimcpservercommon.mockdata.engine;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.UUID;

/** Reflection and text-to-value conversion helpers of the engine. */
final class TypeSupport {

    private TypeSupport() {
    }

    static Class<?> raw(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType p) {
            return raw(p.getRawType());
        }
        if (t instanceof GenericArrayType g) {
            return java.lang.reflect.Array.newInstance(raw(g.getGenericComponentType()), 0).getClass();
        }
        if (t instanceof WildcardType w) {
            return w.getUpperBounds().length > 0 ? raw(w.getUpperBounds()[0]) : Object.class;
        }
        return Object.class; // type variable
    }

    static Type arg(Type t, int index) {
        if (t instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        return Object.class;
    }

    static boolean leaf(Class<?> c) {
        return c.isPrimitive() || c.isEnum() || c == String.class || c == CharSequence.class || Number.class.isAssignableFrom(c)
                || c == Boolean.class || c == Character.class || c == UUID.class || c == URI.class || c == Locale.class
                || c.getPackageName().equals("java.time");
    }

    static Class<?> wrapper(Class<?> c) {
        return !c.isPrimitive() ? c : c == int.class ? Integer.class : c == long.class ? Long.class : c == double.class ? Double.class
                : c == boolean.class ? Boolean.class : c == float.class ? Float.class : c == short.class ? Short.class
                : c == byte.class ? Byte.class : c == char.class ? Character.class : Void.class;
    }

    static @Nullable Object defaultFor(Class<?> c) {
        if (!c.isPrimitive()) {
            return null;
        }
        return c == boolean.class ? Boolean.FALSE : c == char.class ? (Object) '\0' : c == long.class ? (Object) 0L
                : c == double.class ? (Object) 0.0 : c == float.class ? (Object) 0f : c == short.class ? (Object) (short) 0
                : c == byte.class ? (Object) (byte) 0 : (Object) 0;
    }

    /** Converts text (a Datafaker result or an AI value) to {@code target}; {@code null} when it does not fit. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static @Nullable Object coerce(String text, Class<?> target) {
        String s = text.strip();
        try {
            if (target == String.class || target == CharSequence.class || target == Object.class) {
                return text;
            }
            if (target == int.class || target == Integer.class) {
                return Integer.parseInt(number(s));
            }
            if (target == long.class || target == Long.class) {
                return Long.parseLong(number(s));
            }
            if (target == short.class || target == Short.class) {
                return Short.parseShort(number(s));
            }
            if (target == byte.class || target == Byte.class) {
                return Byte.parseByte(number(s));
            }
            if (target == double.class || target == Double.class) {
                return Double.parseDouble(decimal(s));
            }
            if (target == float.class || target == Float.class) {
                return Float.parseFloat(decimal(s));
            }
            if (target == BigDecimal.class) {
                return new BigDecimal(decimal(s));
            }
            if (target == BigInteger.class) {
                return new BigInteger(number(s));
            }
            if (target == boolean.class || target == Boolean.class) {
                if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) {
                    return null;
                }
                return Boolean.parseBoolean(s);
            }
            if (target == char.class || target == Character.class) {
                return s.isEmpty() ? null : s.charAt(0);
            }
            if (target == UUID.class) {
                return UUID.fromString(s);
            }
            if (target == URI.class) {
                return URI.create(s);
            }
            if (target == Locale.class) {
                return Locale.forLanguageTag(s);
            }
            if (target.isEnum()) {
                for (Object c : target.getEnumConstants()) {
                    if (((Enum) c).name().equalsIgnoreCase(s)) {
                        return c;
                    }
                }
                return null;
            }
            return time(s, target);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String number(String s) {
        String t = s.replace(",", "").replace("_", "");
        int dot = t.indexOf('.');
        return dot >= 0 ? t.substring(0, dot) : t;
    }

    private static String decimal(String s) {
        return s.replaceAll("[^0-9.+-eE]", "");
    }

    private static @Nullable Object time(String s, Class<?> target) {
        // Datafaker prints timestamps as "yyyy-MM-dd HH:mm:ss.nnnnnnnnn"
        String iso = s.length() > 10 && s.charAt(10) == ' ' ? s.replace(' ', 'T') : s;
        if (target == LocalDate.class) {
            return LocalDate.parse(iso.length() >= 10 ? iso.substring(0, 10) : iso);
        }
        if (target == LocalDateTime.class) {
            return LocalDateTime.parse(iso.endsWith("Z") ? iso.substring(0, iso.length() - 1) : iso);
        }
        if (target == LocalTime.class) {
            return LocalTime.parse(iso.contains("T") ? iso.substring(iso.indexOf('T') + 1) : iso);
        }
        if (target == Instant.class) {
            return iso.endsWith("Z") ? Instant.parse(iso) : LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
        }
        if (target == OffsetDateTime.class) {
            return iso.endsWith("Z") || iso.contains("+") ? OffsetDateTime.parse(iso) : LocalDateTime.parse(iso).atOffset(ZoneOffset.UTC);
        }
        if (target == ZonedDateTime.class) {
            return iso.endsWith("Z") || iso.contains("+") ? ZonedDateTime.parse(iso) : LocalDateTime.parse(iso).atZone(ZoneOffset.UTC);
        }
        if (target == Duration.class) {
            return Duration.parse(s);
        }
        return null;
    }
}
