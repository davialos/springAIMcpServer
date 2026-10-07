package com.springaimcpservercommon.validation;

import java.util.List;

/**
 * One validation rule. Implement it directly, build one with {@link Rules}, or register a Spring bean of this type
 * and the auto-configured {@link Validator} picks it up.
 *
 * @param <T> the type of object it validates
 */
public interface ValidationRule<T> {

    /**
     * Unique, stable id; ordering overrides refer to it.
     *
     * @return the id
     */
    String id();

    /**
     * The type this rule applies to; objects that are not instances are skipped.
     *
     * @return the target type
     */
    Class<T> targetType();

    /**
     * Contexts the rule runs in.
     *
     * @return the scope, {@link Scope#ANY} by default
     */
    default Scope scope() {
        return Scope.ANY;
    }

    /**
     * Default execution order, lower first; see {@link OrderOverride} to change it per context.
     *
     * @return the order
     */
    default int order() {
        return 0;
    }

    /**
     * Whether an {@link Severity#ERROR} from this rule stops all later rules.
     *
     * @return true to stop
     */
    default boolean stopOnFailure() {
        return false;
    }

    /**
     * Runs the rule.
     *
     * @param target the object to check
     * @param ctx    where in the application this happens
     * @return the violations, empty when the target passes
     */
    List<Violation> validate(T target, ValidationContext ctx);
}
