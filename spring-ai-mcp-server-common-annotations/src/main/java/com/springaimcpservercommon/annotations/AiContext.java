package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes what a JPA entity, Spring service, controller or method means, in plain English, for the LLM.
 *
 * <p>On an {@code @Entity} it makes the entity visible to the catalog (only annotated attributes are exposed,
 * see {@link AiEntityProperty}). On a service it provides the context for its {@link AiExposedAction actions}.
 * On a controller it is descriptive context only — controllers never become tools.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AiContext {

    /**
     * Plain-English meaning of the element for the LLM. Mandatory, at most 1024 characters, no secrets.
     *
     * @return the description
     */
    String description();

    /**
     * Domain terms or synonyms the AI should associate with this element (also used by tool search).
     *
     * @return keywords, possibly empty
     */
    String[] keywords() default {};

    /**
     * Stable logical name; defaults to the simple class or method name.
     *
     * @return the logical name or empty for the default
     */
    String name() default "";

    /**
     * Data classification of the element (drives access control, masking and provider routing).
     *
     * @return the classification; {@link Classification#INHERIT} is not allowed on types
     */
    Classification classification() default Classification.INTERNAL;
}
