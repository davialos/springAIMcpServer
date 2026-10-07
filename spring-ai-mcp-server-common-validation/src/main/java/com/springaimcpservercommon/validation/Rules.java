package com.springaimcpservercommon.validation;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/** Fluent construction of {@link ValidationRule}s from lambdas. */
public final class Rules {

    private Rules() {
    }

    /**
     * Starts a rule for a type.
     *
     * @param type the validated type
     * @param <T>  the validated type
     * @return the builder
     */
    public static <T> RuleBuilder<T> forType(Class<T> type) {
        return new RuleBuilder<>(type);
    }

    /**
     * Builder for a lambda-based rule.
     *
     * @param <T> the validated type
     */
    public static final class RuleBuilder<T> {

        private final Class<T> type;
        private String id = "";
        private Scope scope = Scope.ANY;
        private int order;
        private boolean stopOnFailure;

        private RuleBuilder(Class<T> type) {
            this.type = type;
        }

        /**
         * Sets the id (required).
         *
         * @param value the id
         * @return this
         */
        public RuleBuilder<T> id(String value) {
            this.id = value;
            return this;
        }

        /**
         * Sets the scope.
         *
         * @param value the scope
         * @return this
         */
        public RuleBuilder<T> scope(Scope value) {
            this.scope = value;
            return this;
        }

        /**
         * Sets the default order.
         *
         * @param value the order
         * @return this
         */
        public RuleBuilder<T> order(int value) {
            this.order = value;
            return this;
        }

        /**
         * Makes an error from this rule stop later rules.
         *
         * @return this
         */
        public RuleBuilder<T> stopOnFailure() {
            this.stopOnFailure = true;
            return this;
        }

        /**
         * Finishes with a simple predicate; failing yields one error violation.
         *
         * @param ok      true when the target is acceptable
         * @param field   the field reported
         * @param message the message reported (also used as code when no code is given: the rule id)
         * @return the rule
         */
        public ValidationRule<T> check(Predicate<T> ok, @Nullable String field, String message) {
            String ruleId = requireId();
            return build(
                    (t, ctx) -> ok.test(t) ? List.of() : List.of(Violation.error(ruleId, field, ruleId, message)));
        }

        /**
         * Finishes with a context-aware predicate.
         *
         * @param ok      true when the target is acceptable in the context
         * @param field   the field reported
         * @param message the message reported
         * @return the rule
         */
        public ValidationRule<T> check(BiPredicateLike<T> ok, @Nullable String field, String message) {
            String ruleId = requireId();
            return build((t, ctx) -> ok.test(t, ctx) ? List.of()
                    : List.of(Violation.error(ruleId, field, ruleId, message)));
        }

        /**
         * Finishes with a function producing arbitrary violations.
         *
         * @param fn the function
         * @return the rule
         */
        public ValidationRule<T> validate(BiFunction<T, ValidationContext, List<Violation>> fn) {
            requireId();
            return build(fn);
        }

        /**
         * Convenience: a property that must be non-null.
         *
         * @param getter the property
         * @param field  its name
         * @return the rule
         */
        public ValidationRule<T> notNull(Function<T, @Nullable Object> getter, String field) {
            return check((Predicate<T>) t -> getter.apply(t) != null, field, field + " is required");
        }

        /**
         * Convenience: a string property that must not be blank.
         *
         * @param getter the property
         * @param field  its name
         * @return the rule
         */
        public ValidationRule<T> notBlank(Function<T, @Nullable String> getter, String field) {
            return check((Predicate<T>) t -> {
                String v = getter.apply(t);
                return v != null && !v.isBlank();
            }, field, field + " must not be blank");
        }

        private String requireId() {
            if (id.isBlank()) {
                throw new IllegalStateException("A validation rule needs an id");
            }
            return id;
        }

        private ValidationRule<T> build(BiFunction<T, ValidationContext, List<Violation>> fn) {
            return new LambdaRule<>(id, type, scope, order, stopOnFailure, fn);
        }
    }

    /**
     * Context-aware predicate.
     *
     * @param <T> the validated type
     */
    @FunctionalInterface
    public interface BiPredicateLike<T> {
        /**
         * Tests.
         *
         * @param target the object
         * @param ctx    the context
         * @return true when acceptable
         */
        boolean test(T target, ValidationContext ctx);
    }

    private record LambdaRule<T>(String id, Class<T> targetType, Scope scope, int order, boolean stopOnFailure,
                                 BiFunction<T, ValidationContext, List<Violation>> fn)
            implements ValidationRule<T> {

        @Override
        public List<Violation> validate(T target, ValidationContext ctx) {
            return fn.apply(target, ctx);
        }
    }
}
