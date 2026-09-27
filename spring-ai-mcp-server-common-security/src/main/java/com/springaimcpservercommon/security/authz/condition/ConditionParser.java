package com.springaimcpservercommon.security.authz.condition;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses {@code dai_grant.conditions} into typed {@link GrantConditions}. The language is deliberately small and
 * closed; anything not listed below is rejected with {@link ConditionParseException} (fail closed).
 *
 * <h2>Shape</h2>
 * A JSON object whose entries are all AND-ed:
 * <pre>{@code
 * {
 *   "principal.region":  { "in": ["EU"] },
 *   "environment.tier":  { "eq": "PROD" },
 *   "principal.level":   { "gte": 3, "lte": 7 },
 *   "time": { "between": ["08:00", "18:00"], "zone": "Europe/Berlin", "days": ["MON","TUE","WED","THU","FRI"] }
 * }
 * }</pre>
 *
 * <h2>Attribute keys</h2>
 * {@code <namespace>.<name>} with namespace {@code principal | environment | request | resource} and
 * {@code name} matching {@code [A-Za-z][A-Za-z0-9_-]{0,63}} (see {@link ConditionContext} for what each resolves to).
 *
 * <h2>Operators</h2>
 * An attribute maps to an object of 1..8 operators, all of which must hold:
 * <ul>
 *   <li>{@code eq}, {@code ne}: scalar operand (string, number, boolean);</li>
 *   <li>{@code in}, {@code notIn}: non-empty array (≤ 64) of scalars; for multi-valued attributes {@code in} requires
 *       every element to be listed and {@code notIn} requires no element to be listed;</li>
 *   <li>{@code contains}: scalar operand, attribute must be multi-valued;</li>
 *   <li>{@code startsWith}: non-empty string;</li>
 *   <li>{@code gt}, {@code gte}, {@code lt}, {@code lte}: number;</li>
 *   <li>{@code exists}: boolean.</li>
 * </ul>
 * A missing attribute makes every operator false except {@code exists: false}. Strings compare case-sensitively,
 * numbers numerically, different types never match.
 *
 * <h2>Time window</h2>
 * Key {@code time}: {@code between} = two {@code HH:mm} strings (start inclusive, end exclusive, start after end wraps
 * midnight), {@code zone} = IANA zone id (mandatory, no implicit server zone), optional {@code days} = array of
 * {@code MON..SUN}.
 *
 * <h2>Limits</h2>
 * ≤ 8 KiB of JSON, ≤ 32 entries. There is no OR, no nesting, no expressions and no function calls.
 */
public final class ConditionParser {

    /** Maximum accepted JSON length in characters. */
    public static final int MAX_JSON_LENGTH = 8 * 1024;
    /** Maximum number of top-level entries. */
    public static final int MAX_ENTRIES = 32;
    /** Maximum operators per attribute. */
    public static final int MAX_OPERATORS = 8;
    /** Maximum operands of {@code in}/{@code notIn}. */
    public static final int MAX_LIST = 64;
    /** Maximum length of a string operand. */
    public static final int MAX_STRING = 256;

    private static final Pattern ATTRIBUTE_KEY =
            Pattern.compile("^(principal|environment|request|resource)\\.[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final Set<String> TIME_KEYS = Set.of("between", "zone", "days");

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Parses conditions.
     *
     * @param conditionsJson JSON object text; {@code null} or blank means "no conditions"
     * @return parsed conditions
     * @throws ConditionParseException if malformed or unsupported
     */
    public GrantConditions parse(@Nullable String conditionsJson) {
        if (conditionsJson == null || conditionsJson.isBlank()) {
            return new GrantConditions(List.of());
        }
        if (conditionsJson.length() > MAX_JSON_LENGTH) {
            throw new ConditionParseException("conditions exceed " + MAX_JSON_LENGTH + " characters");
        }
        Object root;
        try {
            root = json.readValue(conditionsJson, Object.class);
        } catch (RuntimeException e) {
            throw new ConditionParseException("conditions are not valid JSON", e);
        }
        return parseTree(root);
    }

    /**
     * Parses an already decoded JSON tree (maps, lists, strings, numbers, booleans).
     *
     * @param root decoded JSON value
     * @return parsed conditions
     * @throws ConditionParseException if malformed or unsupported
     */
    public GrantConditions parseTree(@Nullable Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            throw new ConditionParseException("conditions must be a JSON object");
        }
        if (map.size() > MAX_ENTRIES) {
            throw new ConditionParseException("more than " + MAX_ENTRIES + " conditions");
        }
        List<Condition> conditions = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("time".equals(key)) {
                conditions.add(parseTime(entry.getValue()));
            } else if (ATTRIBUTE_KEY.matcher(key).matches()) {
                conditions.add(parseAttribute(key, entry.getValue()));
            } else {
                throw new ConditionParseException("unknown condition key '" + abbreviate(key) + "'");
            }
        }
        return new GrantConditions(conditions);
    }

    private Condition.Attribute parseAttribute(String path, @Nullable Object spec) {
        if (!(spec instanceof Map<?, ?> ops) || ops.isEmpty()) {
            throw new ConditionParseException("condition '" + path + "' must be a non-empty operator object");
        }
        if (ops.size() > MAX_OPERATORS) {
            throw new ConditionParseException("too many operators on '" + path + "'");
        }
        List<Condition.Operation> operations = new ArrayList<>();
        for (Map.Entry<?, ?> op : ops.entrySet()) {
            String name = String.valueOf(op.getKey());
            Object operand = op.getValue();
            Condition.Operation operation = switch (name) {
                case "eq" -> new Condition.Operation.Eq(scalar(path, name, operand));
                case "ne" -> new Condition.Operation.Ne(scalar(path, name, operand));
                case "in" -> new Condition.Operation.In(scalarList(path, name, operand));
                case "notIn" -> new Condition.Operation.NotIn(scalarList(path, name, operand));
                case "contains" -> new Condition.Operation.Contains(scalar(path, name, operand));
                case "startsWith" -> {
                    if (!(operand instanceof String s) || s.isEmpty() || s.length() > MAX_STRING) {
                        throw new ConditionParseException("'startsWith' on '" + path + "' needs a non-empty string");
                    }
                    yield new Condition.Operation.StartsWith(s);
                }
                case "gt" -> new Condition.Operation.Compare(Condition.Operation.Comparison.GT, number(path, name, operand));
                case "gte" -> new Condition.Operation.Compare(Condition.Operation.Comparison.GTE, number(path, name, operand));
                case "lt" -> new Condition.Operation.Compare(Condition.Operation.Comparison.LT, number(path, name, operand));
                case "lte" -> new Condition.Operation.Compare(Condition.Operation.Comparison.LTE, number(path, name, operand));
                case "exists" -> {
                    if (!(operand instanceof Boolean b)) {
                        throw new ConditionParseException("'exists' on '" + path + "' needs a boolean");
                    }
                    yield new Condition.Operation.Exists(b);
                }
                default -> throw new ConditionParseException("unknown operator '" + abbreviate(name) + "' on '" + path + "'");
            };
            operations.add(operation);
        }
        return new Condition.Attribute(path, operations);
    }

    private Condition.TimeWindow parseTime(@Nullable Object spec) {
        if (!(spec instanceof Map<?, ?> map)) {
            throw new ConditionParseException("'time' must be an object");
        }
        for (Object key : map.keySet()) {
            if (!TIME_KEYS.contains(String.valueOf(key))) {
                throw new ConditionParseException("unknown 'time' property '" + abbreviate(String.valueOf(key)) + "'");
            }
        }
        if (!(map.get("between") instanceof List<?> between) || between.size() != 2) {
            throw new ConditionParseException("'time.between' must be [\"HH:mm\", \"HH:mm\"]");
        }
        LocalTime start = localTime(between.get(0));
        LocalTime end = localTime(between.get(1));
        if (!(map.get("zone") instanceof String zoneText) || zoneText.isBlank()) {
            throw new ConditionParseException("'time.zone' is mandatory");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneText);
        } catch (DateTimeException e) {
            throw new ConditionParseException("'time.zone' is not a valid zone id", e);
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        Object daysSpec = map.get("days");
        if (daysSpec != null) {
            if (!(daysSpec instanceof List<?> list) || list.isEmpty() || list.size() > 7) {
                throw new ConditionParseException("'time.days' must be a non-empty array of MON..SUN");
            }
            for (Object day : list) {
                days.add(dayOfWeek(day));
            }
        }
        try {
            return new Condition.TimeWindow(start, end, zone, days);
        } catch (IllegalArgumentException e) {
            throw new ConditionParseException("'time.between' must not be empty", e);
        }
    }

    private static LocalTime localTime(@Nullable Object value) {
        if (!(value instanceof String text)) {
            throw new ConditionParseException("time must be a \"HH:mm\" string");
        }
        try {
            return LocalTime.parse(text, HH_MM);
        } catch (DateTimeParseException e) {
            throw new ConditionParseException("time must be \"HH:mm\"", e);
        }
    }

    private static DayOfWeek dayOfWeek(@Nullable Object value) {
        if (value instanceof String text) {
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day.name().substring(0, 3).equals(text)) {
                    return day;
                }
            }
        }
        throw new ConditionParseException("day must be one of MON..SUN");
    }

    private static Object scalar(String path, String op, @Nullable Object operand) {
        return switch (operand) {
            case String s when s.length() <= MAX_STRING -> s;
            case Boolean b -> b;
            case Number n -> toDecimal(path, op, n);
            case null, default -> throw new ConditionParseException("'" + op + "' on '" + path + "' needs a scalar operand");
        };
    }

    private static List<Object> scalarList(String path, String op, @Nullable Object operand) {
        if (!(operand instanceof List<?> list) || list.isEmpty() || list.size() > MAX_LIST) {
            throw new ConditionParseException("'" + op + "' on '" + path + "' needs a non-empty array of at most "
                    + MAX_LIST + " scalars");
        }
        List<Object> values = new ArrayList<>(list.size());
        for (Object element : list) {
            values.add(scalar(path, op, element));
        }
        return values;
    }

    private static BigDecimal number(String path, String op, @Nullable Object operand) {
        if (!(operand instanceof Number n)) {
            throw new ConditionParseException("'" + op + "' on '" + path + "' needs a number");
        }
        return toDecimal(path, op, n);
    }

    private static BigDecimal toDecimal(String path, String op, Number number) {
        BigDecimal decimal = switch (number) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d when Double.isFinite(d) -> new BigDecimal(d.toString());
            case Float f when Float.isFinite(f) -> new BigDecimal(f.toString());
            case Double ignored -> null;
            case Float ignored -> null;
            default -> BigDecimal.valueOf(number.longValue());
        };
        if (decimal == null) {
            throw new ConditionParseException("'" + op + "' on '" + path + "' needs a finite number");
        }
        return decimal;
    }

    private static String abbreviate(String text) {
        String safe = text.replaceAll("[^A-Za-z0-9_.-]", "?");
        return safe.length() > 40 ? safe.substring(0, 40) + "…" : safe;
    }
}
