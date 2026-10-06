package com.springaimcpservercommon.ruleengine.library;

import com.springaimcpservercommon.ruleengine.domain.DataType;
import com.springaimcpservercommon.ruleengine.domain.Model.SysAttribute;
import com.springaimcpservercommon.ruleengine.domain.Model.SysObject;
import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the JSON an application sends ({@code {"customer": {"age": 20}}}) into the values CEL expects: integers as
 * {@code long}, decimals as {@code double}, dates and timestamps as protobuf {@code Timestamp}s, lists as lists — following the
 * data types of the parameter library. Attributes the library does not know pass through (numbers normalised).
 */
@Component
public class ContextCoercer {

    /**
     * Converts a request context.
     *
     * @param context object code → attribute → value
     * @param library the library the types come from
     * @return CEL variables: object code → attribute → typed value
     * @throws IllegalArgumentException when a supplied value cannot be converted to its attribute's type
     */
    public Map<String, Object> coerce(Map<String, Map<String, Object>> context, ParameterLibrary library) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> object : context.entrySet()) {
            SysObject def = library.objects().get(object.getKey());
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> a : object.getValue().entrySet()) {
                SysAttribute attr = def == null ? null
                        : def.attributes().stream().filter(x -> x.code().equals(a.getKey())).findFirst().orElse(null);
                try {
                    attributes.put(a.getKey(), attr == null ? generic(a.getValue()) : typed(a.getValue(), attr.dataType()));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(object.getKey() + "." + a.getKey() + ": cannot read "
                            + a.getValue() + " as " + (attr == null ? "a value" : attr.dataType()), e);
                }
            }
            out.put(object.getKey(), attributes);
        }
        return out;
    }

    private static Object typed(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case STRING -> String.valueOf(value);
            case INTEGER -> value instanceof Number n ? (Object) n.longValue() : (Object) Long.parseLong(value.toString().trim());
            case DECIMAL -> value instanceof Number n ? (Object) n.doubleValue() : (Object) new BigDecimal(value.toString().trim()).doubleValue();
            case BOOLEAN -> value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString().trim());
            case DATE -> timestamp(LocalDate.parse(value.toString().trim()).atStartOfDay().toInstant(ZoneOffset.UTC));
            case TIMESTAMP -> timestamp(value instanceof Number n ? Instant.ofEpochMilli(n.longValue())
                    : OffsetDateTime.parse(value.toString().trim()).toInstant());
            case STRING_LIST -> list(value, v -> String.valueOf(v));
            case INTEGER_LIST -> list(value, v -> v instanceof Number n ? n.longValue() : Long.parseLong(v.toString().trim()));
            case DECIMAL_LIST -> list(value, v -> v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString().trim()));
        };
    }

    /** CEL's timestamp is the protobuf Timestamp. */
    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static List<Object> list(Object value, java.util.function.Function<Object, Object> each) {
        if (!(value instanceof Iterable<?> items)) {
            throw new IllegalArgumentException("a list is expected");
        }
        List<Object> out = new ArrayList<>();
        items.forEach(i -> out.add(each.apply(i)));
        return out;
    }

    private static Object generic(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), generic(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(ContextCoercer::generic).toList();
        }
        return value;
    }
}
