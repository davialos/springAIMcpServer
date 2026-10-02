package com.springaimcpservercommon.loadtest.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the test field holding the target of a {@link K6LoadTest}: an {@code int}/{@code Integer} port, or a
 * {@code String}/{@code URI} base URL. Not needed with Spring's {@code @LocalServerPort}, which is recognised by
 * name.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface K6Target {
}
