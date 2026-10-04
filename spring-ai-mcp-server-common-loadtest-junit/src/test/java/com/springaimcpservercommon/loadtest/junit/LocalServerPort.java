package com.springaimcpservercommon.loadtest.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stands in for Spring Boot's {@code @LocalServerPort}: the extension recognises it by simple name. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface LocalServerPort {
}
