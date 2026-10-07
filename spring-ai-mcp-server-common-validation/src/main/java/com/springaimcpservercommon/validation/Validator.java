package com.springaimcpservercommon.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The entry point: {@code validator.validate(target, context)} at any stage of the application. Immutable and
 * thread-safe. Which rules run is decided by the target's type and the {@link ValidationContext}; in which order,
 * by each rule's default order refined by {@link OrderOverride}s.
 *
 * <p>A rule that throws is reported as an {@code rule_error} violation (fail closed); its exception message is
 * logged by type only, never its content.
 */
public final class Validator {

    private static final Logger LOG = LoggerFactory.getLogger(Validator.class);

    private final List<ValidationRule<?>> rules;
    private final List<OrderOverride> overrides;

    private Validator(List<ValidationRule<?>> rules, List<OrderOverride> overrides) {
        this.rules = List.copyOf(rules);
        this.overrides = List.copyOf(overrides);
    }

    /**
     * Starts a builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates, collecting every violation.
     *
     * @param target the object to check
     * @param ctx    where in the application this happens
     * @return the result
     */
    public ValidationResult validate(Object target, ValidationContext ctx) {
        return validate(target, ctx, ValidationOptions.COLLECT_ALL);
    }

    /**
     * Validates with options.
     *
     * @param target  the object to check
     * @param ctx     where in the application this happens
     * @param options per-call options
     * @return the result
     */
    public ValidationResult validate(Object target, ValidationContext ctx, ValidationOptions options) {
        List<Planned> plan = plan(target, ctx);
        List<Violation> violations = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        for (Planned p : plan) {
            executed.add(p.rule.id());
            boolean failed = run(p.rule, target, ctx, violations);
            if (failed && (options.failFast() || p.rule.stopOnFailure())) {
                break;
            }
        }
        return new ValidationResult(violations, executed);
    }

    /**
     * Validates and throws on any error.
     *
     * @param target the object to check
     * @param ctx    where in the application this happens
     * @return the (valid) result, so warnings stay readable
     * @throws ValidationException if invalid
     */
    public ValidationResult validateOrThrow(Object target, ValidationContext ctx) {
        ValidationResult result = validate(target, ctx);
        result.throwIfInvalid();
        return result;
    }

    /**
     * The rules that would run, in order, without running them (for diagnostics and the admin UI).
     *
     * @param target the object
     * @param ctx    the context
     * @return rule ids in execution order
     */
    public List<String> explain(Object target, ValidationContext ctx) {
        return plan(target, ctx).stream().map(p -> p.rule.id()).toList();
    }

    @SuppressWarnings("unchecked")
    private static boolean run(ValidationRule<?> rule, Object target, ValidationContext ctx, List<Violation> out) {
        List<Violation> found;
        try {
            found = ((ValidationRule<Object>) rule).validate(target, ctx);
        } catch (RuntimeException e) {
            LOG.warn("Validation rule {} threw {}", rule.id(), e.getClass().getName());
            found = List.of(Violation.error(rule.id(), null, "rule_error", "Validation could not be completed"));
        }
        out.addAll(found);
        return found.stream().anyMatch(v -> v.severity() == Severity.ERROR);
    }

    private List<Planned> plan(Object target, ValidationContext ctx) {
        List<Planned> plan = new ArrayList<>();
        int index = 0;
        for (ValidationRule<?> rule : rules) {
            int position = index++;
            if (!rule.targetType().isInstance(target) || !rule.scope().matches(ctx)) {
                continue;
            }
            OrderOverride chosen = null;
            int chosenSpecificity = -1;
            for (OrderOverride o : overrides) {
                if (new Glob(o.rulePattern()).matches(rule.id()) && o.scope().matches(ctx)
                        && o.scope().specificity() >= chosenSpecificity) {
                    chosen = o;
                    chosenSpecificity = o.scope().specificity();
                }
            }
            if (chosen != null && chosen.skip()) {
                continue;
            }
            Integer override = chosen == null ? null : chosen.order();
            plan.add(new Planned(rule, override == null ? rule.order() : override, position));
        }
        plan.sort(Comparator.comparingInt((Planned p) -> p.order).thenComparingInt(p -> p.position));
        return plan;
    }

    private record Planned(ValidationRule<?> rule, int order, int position) {
    }

    /** Builds a {@link Validator}. */
    public static final class Builder {

        private final List<ValidationRule<?>> rules = new ArrayList<>();
        private final List<OrderOverride> overrides = new ArrayList<>();

        private Builder() {
        }

        /**
         * Registers a rule.
         *
         * @param rule the rule
         * @return this
         */
        public Builder rule(ValidationRule<?> rule) {
            rules.add(rule);
            return this;
        }

        /**
         * Registers rules.
         *
         * @param more the rules
         * @return this
         */
        public Builder rules(Iterable<? extends ValidationRule<?>> more) {
            more.forEach(rules::add);
            return this;
        }

        /**
         * Registers an ordering override.
         *
         * @param override the override
         * @return this
         */
        public Builder override(OrderOverride override) {
            overrides.add(override);
            return this;
        }

        /**
         * Registers ordering overrides.
         *
         * @param more the overrides
         * @return this
         */
        public Builder overrides(Iterable<OrderOverride> more) {
            more.forEach(overrides::add);
            return this;
        }

        /**
         * Builds the validator.
         *
         * @return the validator
         * @throws IllegalStateException when two rules share an id
         */
        public Validator build() {
            Set<String> ids = new HashSet<>();
            for (ValidationRule<?> r : rules) {
                if (!ids.add(r.id())) {
                    throw new IllegalStateException("Duplicate validation rule id: " + r.id());
                }
            }
            return new Validator(rules, overrides);
        }
    }
}
