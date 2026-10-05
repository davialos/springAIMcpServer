package com.springaimcpservercommon.ruleengine.cel;

import com.springaimcpservercommon.ruleengine.model.Parameter;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The input of one evaluation: the caller's values for library parameters.
 *
 * <p>Values may be flat ({@code "customer.age": 34}) or nested ({@code "customer": {"age": 34}}). They are coerced
 * to the parameter's declared type once per evaluation and only for the parameters a rule actually reads.
 * Not thread-safe: one instance per evaluation. Values are never logged or stored.
 */
public final class Facts {

    private final Map<String, Object> raw;
    private final Map<String, Object> coerced = new HashMap<>();

    /**
     * Wraps the caller's values.
     *
     * @param raw flat or nested values keyed by sys object / attribute code
     */
    public Facts(Map<String, ?> raw) {
        this.raw = new HashMap<>(raw);
    }

    /**
     * Resolves a parameter to a CEL-ready value.
     *
     * @param parameter the parameter
     * @return the coerced value
     * @throws FactException {@code MISSING_PARAMETER} when absent/null, {@code INVALID_PARAMETER} on a type mismatch
     */
    public Object bind(Parameter parameter) throws FactException {
        Object cached = coerced.get(parameter.celName());
        if (cached != null) {
            return cached;
        }
        Object value = lookup(parameter);
        if (value == null) {
            throw new FactException("MISSING_PARAMETER", "no value for " + parameter.celName());
        }
        Object result = coerce(parameter, value);
        coerced.put(parameter.celName(), result);
        return result;
    }

    private @Nullable Object lookup(Parameter p) {
        Object flat = raw.get(p.celName());
        if (flat != null) {
            return flat;
        }
        return raw.get(p.objectCode()) instanceof Map<?, ?> nested ? nested.get(p.attributeCode()) : null;
    }

    private static Object coerce(Parameter p, Object v) throws FactException {
        try {
            return switch (p.dataType()) {
                case STRING -> v instanceof String s ? s : fail(p);
                case INT -> v instanceof Number n ? toLong(n, p) : fail(p);
                case DOUBLE -> v instanceof Number n ? (Object) n.doubleValue() : fail(p);
                case BOOL -> v instanceof Boolean b ? b : fail(p);
                case TIMESTAMP -> v instanceof Instant i ? i : v instanceof String s ? Instant.parse(s) : fail(p);
                case DURATION -> v instanceof Duration d ? d : v instanceof String s ? Duration.parse(s) : fail(p);
                case LIST_STRING -> list(p, v, e -> e instanceof String s ? s : fail(p));
                case LIST_INT -> list(p, v, e -> e instanceof Number n ? toLong(n, p) : fail(p));
                case LIST_DOUBLE -> list(p, v, e -> e instanceof Number n ? (Object) n.doubleValue() : fail(p));
                case MAP -> v instanceof Map<?, ?> m ? m : fail(p);
                case ANY -> v;
            };
        } catch (DateTimeParseException e) {
            throw new FactException("INVALID_PARAMETER", p.celName() + " is not a valid " + p.dataType());
        }
    }

    private static Object toLong(Number n, Parameter p) throws FactException {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d != Math.rint(d) || Double.isInfinite(d)) {
                return fail(p);
            }
            return (long) d;
        }
        return n.longValue();
    }

    @FunctionalInterface
    private interface ElementCoercer {
        Object apply(Object element) throws FactException;
    }

    private static Object list(Parameter p, Object v, ElementCoercer coercer) throws FactException {
        if (!(v instanceof List<?> in)) {
            return fail(p);
        }
        List<Object> out = new ArrayList<>(in.size());
        for (Object e : in) {
            out.add(coercer.apply(e));
        }
        return out;
    }

    private static Object fail(Parameter p) throws FactException {
        throw new FactException("INVALID_PARAMETER", p.celName() + " is not a valid " + p.dataType());
    }
}
