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
