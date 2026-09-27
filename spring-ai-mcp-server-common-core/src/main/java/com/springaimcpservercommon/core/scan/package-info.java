/**
 * Startup scan of the host's Spring beans for {@code @AiExposedAction} and {@code @AiContext} (LLD-02 §3–4).
 *
 * <p>This is the <b>only</b> core package allowed to use Spring ({@code spring-core}, {@code spring-beans},
 * {@code spring-context}, declared optional in the core POM). Everything it produces is Spring-free catalog data.
 */
@NullMarked
package com.springaimcpservercommon.core.scan;

import org.jspecify.annotations.NullMarked;
