/**
 * JUnit 5 integration of the load-test generator: {@link com.springaimcpservercommon.loadtest.junit.K6LoadTest}
 * generates a k6 suite for the project once per test class; {@link com.springaimcpservercommon.loadtest.junit.K6Suite}
 * runs it against the application under test (ADR-0022, LLD-16).
 */
@NullMarked
package com.springaimcpservercommon.loadtest.junit;

import org.jspecify.annotations.NullMarked;
