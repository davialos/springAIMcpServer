package com.springaimcpservercommon.security.authz.condition;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One typed, parsed ABAC predicate. The set of predicates is closed (sealed), so nothing but the operators documented
 * in {@link ConditionParser} can ever be evaluated.
 */
public sealed interface Condition permits Condition.Attribute, Condition.TimeWindow {

    /**
     * Evaluates the predicate. A missing attribute makes every operator false except {@code exists: false}.
     *
     * @param context facts
     * @return {@code true} if the predicate holds
     */
    boolean test(ConditionContext context);

    /**
     * Predicates on one attribute; all operations must hold.
     *
     * @param path       attribute path ({@code principal.region})
     * @param operations operations (AND)
     */
    record Attribute(String path, List<Operation> operations) implements Condition {
        /**
         * Validates and copies.
         */
        public Attribute {
            Objects.requireNonNull(path, "path");
            operations = List.copyOf(operations);
            if (operations.isEmpty()) {
                throw new IllegalArgumentException("at least one operation required");
            }
        }

        @Override
        public boolean test(ConditionContext context) {
            Optional<Object> value = context.resolve(path);
            for (Operation operation : operations) {
                if (!operation.test(value.orElse(null))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Local-time window in a zone, optionally restricted to days of week. {@code start > end} wraps midnight.
     *
     * @param start inclusive start
     * @param end   exclusive end
     * @param zone  zone the window is expressed in
     * @param days  allowed days (empty = every day); for windows wrapping midnight the day of the start applies
     */
    record TimeWindow(LocalTime start, LocalTime end, ZoneId zone, Set<DayOfWeek> days) implements Condition {
        /**
         * Validates and copies.
         */
        public TimeWindow {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(zone, "zone");
            if (start.equals(end)) {
                throw new IllegalArgumentException("empty time window");
            }
            days = Set.copyOf(days);
        }

        @Override
        public boolean test(ConditionContext context) {
            ZonedDateTime local = context.now().atZone(zone);
            LocalTime time = local.toLocalTime();
            DayOfWeek day = local.getDayOfWeek();
            boolean inWindow;
            if (start.isBefore(end)) {
                inWindow = !time.isBefore(start) && time.isBefore(end);
            } else {
                boolean lateSegment = !time.isBefore(start);
                boolean earlySegment = time.isBefore(end);
                inWindow = lateSegment || earlySegment;
                if (earlySegment && !lateSegment) {
                    day = day.minus(1);
                }
            }
            return inWindow && (days.isEmpty() || days.contains(day));
        }
    }

    /**
     * One operator applied to an attribute value (which may be {@code null} when absent). Operand values are
     * {@code String}, {@code Boolean} or {@code BigDecimal}.
     */
    sealed interface Operation {

        /**
         * Tests the attribute value.
         *
         * @param value attribute value or {@code null}
         * @return {@code true} if it holds
         */
        boolean test(@Nullable Object value);

        /** {@code eq}: scalar equality (numbers compared numerically). */
        record Eq(Object operand) implements Operation {
            @Override
            public boolean test(@Nullable Object value) {
                return value != null && !(value instanceof Collection<?>) && Values.same(value, operand);
            }
        }

        /** {@code ne}: scalar inequality; false when absent. */
        record Ne(Object operand) implements Operation {
            @Override
            public boolean test(@Nullable Object value) {
                return value != null && !(value instanceof Collection<?>) && !Values.same(value, operand);
            }
        }

        /**
         * {@code in}: a scalar must be one of the operands; a multi-valued attribute must be non-empty and have
         * <em>every</em> element among the operands (restrictive on purpose).
         */
        record In(List<Object> operands) implements Operation {
            /**
             * Copies operands.
             */
            public In {
                operands = List.copyOf(operands);
            }

            @Override
            public boolean test(@Nullable Object value) {
                if (value == null) {
                    return false;
                }
                if (value instanceof Collection<?> values) {
                    return !values.isEmpty() && values.stream().allMatch(v -> v != null && Values.anySame(v, operands));
                }
                return Values.anySame(value, operands);
            }
        }

        /** {@code notIn}: no value (scalar or element) is among the operands; false when absent. */
        record NotIn(List<Object> operands) implements Operation {
            /**
             * Copies operands.
             */
            public NotIn {
                operands = List.copyOf(operands);
            }

            @Override
            public boolean test(@Nullable Object value) {
                if (value == null) {
                    return false;
                }
                if (value instanceof Collection<?> values) {
                    return values.stream().noneMatch(v -> v != null && Values.anySame(v, operands));
                }
                return !Values.anySame(value, operands);
            }
        }

        /** {@code exists}: presence ({@code true}) or absence ({@code false}). */
        record Exists(boolean expected) implements Operation {
            @Override
            public boolean test(@Nullable Object value) {
                return (value != null) == expected;
            }
        }

        /** {@code contains}: a multi-valued attribute contains the operand. */
        record Contains(Object operand) implements Operation {
            @Override
            public boolean test(@Nullable Object value) {
                return value instanceof Collection<?> values
                        && values.stream().anyMatch(v -> v != null && Values.same(v, operand));
            }
        }

        /** {@code startsWith}: a string attribute starts with the operand. */
        record StartsWith(String prefix) implements Operation {
            @Override
            public boolean test(@Nullable Object value) {
                return value instanceof String s && s.startsWith(prefix);
            }
        }

        /** {@code gt}, {@code gte}, {@code lt}, {@code lte}: numeric comparison; non-numeric values are false. */
        record Compare(Comparison comparison, BigDecimal operand) implements Operation {
            @Override
            public boolean test(@Nullable Object value) {
                BigDecimal number = Values.number(value);
                if (number == null) {
                    return false;
                }
                int c = number.compareTo(operand);
                return switch (comparison) {
                    case GT -> c > 0;
                    case GTE -> c >= 0;
                    case LT -> c < 0;
                    case LTE -> c <= 0;
                };
            }
        }

        /** Numeric comparison kinds. */
        enum Comparison {
            /** Greater than. */
            GT,
            /** Greater than or equal. */
            GTE,
            /** Less than. */
            LT,
            /** Less than or equal. */
            LTE
        }
    }
}
