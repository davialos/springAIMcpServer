package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bounds the data scope of dynamic and AI-originated queries on an entity (LLD-05 §5a).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AiQueryConstraints {

    /**
     * Maximum rows a single dynamic query may return for this entity. The effective limit is the minimum of
     * this value, the query definition, policy layers and the global cap.
     *
     * @return the maximum number of rows, at least 1
     */
    int maxLimit() default 50;

    /**
     * Attributes that must always be constrained by a server-bound value (principal attribute, row policy or
     * pinned request parameter), for example {@code tenantId}. A value supplied only by the model does not count.
     *
     * @return attribute names, possibly empty
     */
    String[] mandatoryFilters() default {};
}
