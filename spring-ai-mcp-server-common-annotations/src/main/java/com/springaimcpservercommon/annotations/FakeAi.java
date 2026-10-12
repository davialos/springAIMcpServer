package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or record component whose realistic value needs understanding rather than a lookup table (a support
 * ticket description, a product review, a plausible medical note). The engine leaves it to the AI model and fills it
 * from a pool of generated values, never with one model call per generated object. Without a model the field gets a
 * placeholder and the gap is reported.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface FakeAi {

    /**
     * What the value should look like, in plain English; goes into the prompt. Never put real data here.
     *
     * @return the hint, e.g. {@code a polite complaint about a late delivery, 2 sentences}
     */
    String value() default "";
}
