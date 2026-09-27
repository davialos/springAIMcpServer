package com.springaimcpservercommon.security.authz.condition;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Value comparison rules of the condition language: numbers compare numerically, strings and booleans exactly
 * (case-sensitive), different types never match.
 */
final class Values {

    private Values() {
    }

    static boolean same(Object value, Object operand) {
        BigDecimal a = number(value);
        BigDecimal b = number(operand);
        if (a != null || b != null) {
            return a != null && b != null && a.compareTo(b) == 0;
        }
        if (value instanceof String || value instanceof Boolean) {
            return value.equals(operand);
        }
        return false;
    }

    static boolean anySame(Object value, List<Object> operands) {
        for (Object operand : operands) {
            if (same(value, operand)) {
                return true;
            }
        }
        return false;
    }

    static @Nullable BigDecimal number(@Nullable Object value) {
        return switch (value) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Integer i -> BigDecimal.valueOf(i.longValue());
            case Long l -> BigDecimal.valueOf(l);
            case Short s -> BigDecimal.valueOf(s.longValue());
            case Byte b -> BigDecimal.valueOf(b.longValue());
            case Double d when Double.isFinite(d) -> new BigDecimal(d.toString());
            case Float f when Float.isFinite(f) -> new BigDecimal(f.toString());
            case null, default -> null;
        };
    }
}
