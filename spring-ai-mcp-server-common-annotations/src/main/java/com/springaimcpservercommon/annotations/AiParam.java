package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a parameter of an {@link AiExposedAction} for the tool's JSON schema.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AiParam {

    /**
     * What the parameter means and which values are valid.
     *
     * @return the description
     */
    String description();

    /**
     * Extra notes in plain English that help the AI decide whether a value is the right one: what it looks like,
     * what it is not, where it comes from, common mix-ups with similar parameters. Added to the tool's parameter
     * description and to the searchable catalog knowledge. Never put secrets or real data here.
     *
     * @return the notes, empty for none
     */
    String details() default "";

    /**
     * Examples of valid values, as they would be written in the tool call. Ignored when the parameter is
     * {@link #sensitive()}. Use made-up values, never real data.
     *
     * @return up to five examples
     */
    String[] examples() default {};

    /**
     * Parameter name; only needed when the host is compiled without {@code -parameters}.
     *
     * @return the name or empty to use the reflected name
     */
    String name() default "";

    /**
     * Whether the model must supply the parameter.
     *
     * @return whether the parameter is required
     */
    boolean required() default true;

    /**
     * If {@code true} the value is redacted in traces and audit records.
     *
     * @return whether the value is sensitive
     */
    boolean sensitive() default false;
}
