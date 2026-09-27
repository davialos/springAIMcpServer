package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes an attribute of an {@link AiContext}-annotated entity (or a DTO/record component) and explains what
 * the column represents in the real world. Attributes without this annotation stay hidden (LLD-02 §4).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD})
public @interface AiEntityProperty {

    /**
     * What the value represents in the real world. Mandatory, at most 256 characters.
     *
     * @return the meaning
     */
    String meaning();

    /**
     * If {@code true} the value is never sent to the LLM, never shown and always masked (passwords, SSNs, …).
     *
     * @return whether the attribute is sensitive
     */
    boolean sensitive() default false;

    /**
     * If {@code true} the attribute may appear as an editable field in a user-reviewed write proposal (LLD-11).
     *
     * @return whether the attribute is writable through reviewed proposals
     */
    boolean writable() default false;

    /**
     * Classification of this attribute; {@link Classification#INHERIT} uses the entity's classification.
     *
     * @return the classification
     */
    Classification classification() default Classification.INHERIT;
}
