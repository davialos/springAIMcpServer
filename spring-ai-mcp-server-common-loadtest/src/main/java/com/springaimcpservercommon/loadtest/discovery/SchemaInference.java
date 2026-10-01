package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.model.ArraySchema;
import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ObjectSchema;
import com.springaimcpservercommon.loadtest.model.Property;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import com.springaimcpservercommon.loadtest.model.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Infers request schemas from observed values (recorded traffic): JSON bodies and query/path strings. Deliberately
 * loose — types and formats only, no length or range constraints — because a recording shows what was sent, not
 * what is allowed. Samples are merged pairwise: a property is required only if every sample had it.
 */
public final class SchemaInference {

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DATE_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern INTEGER = Pattern.compile("^-?\\d{1,18}$");
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d+\\.\\d+$");

    private SchemaInference() {
    }

    /**
     * Schema of one JSON value.
     *
     * @param value sample
     * @return inferred schema; {@code null} for JSON {@code null} (no type information)
     */
    public static @Nullable Schema infer(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isObject()) {
            Map<String, Property> props = new LinkedHashMap<>();
            for (var e : value.properties()) {
                Schema s = infer(e.getValue());
                boolean present = s != null;
                props.put(e.getKey(), new Property(s != null ? s : ScalarSchema.of(ScalarType.STRING, null), present,
                        Names.isSensitive(e.getKey()), null));
            }
            return new ObjectSchema(props, props.isEmpty());
        }
        if (value.isArray()) {
            Schema items = null;
            int n = 0;
            for (JsonNode element : value) {
                if (n++ >= 50) {
                    break;
                }
                items = merge(items, infer(element));
            }
            return new ArraySchema(items != null ? items : ObjectSchema.freeFormObject(), null, null);
        }
        if (value.isBoolean()) {
            return ScalarSchema.of(ScalarType.BOOLEAN, null);
        }
        if (value.isIntegralNumber()) {
            return ScalarSchema.of(ScalarType.INTEGER, value.canConvertToInt() ? "int32" : "int64");
        }
        if (value.isNumber()) {
            return ScalarSchema.of(ScalarType.NUMBER, "double");
        }
        return ScalarSchema.of(ScalarType.STRING, stringFormat(value.asString()));
    }

    /**
     * Schema of a value seen as text (path segment, query parameter, header).
     *
     * @param value sample text
     * @return inferred scalar schema
     */
    public static ScalarSchema inferText(String value) {
        if (INTEGER.matcher(value).matches()) {
            return ScalarSchema.of(ScalarType.INTEGER, "int64");
        }
        if (DECIMAL.matcher(value).matches()) {
            return ScalarSchema.of(ScalarType.NUMBER, "double");
        }
        if (value.equals("true") || value.equals("false")) {
            return ScalarSchema.of(ScalarType.BOOLEAN, null);
        }
        return ScalarSchema.of(ScalarType.STRING, stringFormat(value));
    }

    private static @Nullable String stringFormat(String s) {
        if (UUID.matcher(s).matches()) {
            return "uuid";
        }
        if (EMAIL.matcher(s).matches()) {
            return "email";
        }
        if (DATE.matcher(s).matches()) {
            return "date";
        }
        if (DATE_TIME.matcher(s).matches()) {
            return "date-time";
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return "uri";
        }
        return null;
    }

    /**
     * Merges two inferred schemas (either may be {@code null}).
     *
     * @param a first
     * @param b second
     * @return a schema that accepts both samples
     */
    public static @Nullable Schema merge(@Nullable Schema a, @Nullable Schema b) {
        if (a == null) {
            return b;
        }
        if (b == null || a.equals(b)) {
            return a;
        }
        if (a instanceof ObjectSchema oa && b instanceof ObjectSchema ob) {
            Map<String, Property> props = new LinkedHashMap<>();
            for (var e : oa.properties().entrySet()) {
                Property other = ob.properties().get(e.getKey());
                Property p = e.getValue();
                props.put(e.getKey(), other == null
                        ? new Property(p.schema(), false, p.sensitive(), null)
                        : new Property(merge(p.schema(), other.schema()), p.required() && other.required(),
                        p.sensitive() || other.sensitive(), null));
            }
            for (var e : ob.properties().entrySet()) {
                if (!props.containsKey(e.getKey())) {
                    Property p = e.getValue();
                    props.put(e.getKey(), new Property(p.schema(), false, p.sensitive(), null));
                }
            }
            return new ObjectSchema(props, props.isEmpty());
        }
        if (a instanceof ArraySchema aa && b instanceof ArraySchema ab) {
            Schema items = merge(aa.items(), ab.items());
            return new ArraySchema(items != null ? items : ObjectSchema.freeFormObject(), null, null);
        }
        if (a instanceof ScalarSchema sa && b instanceof ScalarSchema sb) {
            if (sa.type() == sb.type()) {
                String format = sa.format() != null && sa.format().equals(sb.format()) ? sa.format()
                        : sa.type() == ScalarType.INTEGER ? "int64" : null;
                return ScalarSchema.of(sa.type(), format);
            }
            List<ScalarType> numeric = List.of(ScalarType.INTEGER, ScalarType.NUMBER);
            if (numeric.contains(sa.type()) && numeric.contains(sb.type())) {
                return ScalarSchema.of(ScalarType.NUMBER, "double");
            }
            return ScalarSchema.of(ScalarType.STRING, null);
        }
        // Object vs array vs scalar: the API accepts several shapes; nothing safe to generate but free-form.
        return ObjectSchema.freeFormObject().equals(a) ? b : a instanceof ObjectSchema ? a : b;
    }
}
