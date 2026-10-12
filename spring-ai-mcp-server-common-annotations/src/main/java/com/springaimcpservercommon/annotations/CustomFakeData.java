package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fills a field or record component with a domain-specific value from your own {@link CustomFakerProvider}
 * (security tokens, account numbers with check digits, ids that must exist in the system under test).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface CustomFakeData {

    /**
     * The provider. With Spring Boot it is looked up as a bean first and created with its public no-argument constructor otherwise.
     *
     * @return the provider class
     */
    Class<? extends CustomFakerProvider> provider();
}
