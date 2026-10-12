package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says how the mock-data engine fills a field or record component: with a Datafaker expression such as
 * {@code name.fullName}, {@code address.city} or {@code number.numberBetween '1','100'} (the text inside
 * {@code #{…}}). Unannotated fields are filled from their name and type.
 *
 * <p>For a collection the expression is evaluated once per element.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface FakeData {

    /**
     * The Datafaker expression without the surrounding {@code #{ }}.
     *
     * @return the expression, e.g. {@code internet.emailAddress}
     */
    String value();

    /**
     * Locale tag for this field only ({@code de}, {@code fr-CA}); empty uses the engine's locale.
     *
     * @return the locale tag
     */
    String locale() default "";
}
