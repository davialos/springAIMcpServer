/**
 * Reading the host's own record versions (LLD-11 §5): the SPI the write path uses to remember which version of a record a
 * change was proposed against, to detect that it changed since, and to link an applied change to the host revision it
 * produced. The library never writes version rows itself; the host's mechanism (JPA {@code @Version}, Envers, history
 * tables) stays the single owner of data history.
 */
@NullMarked
package com.springaimcpservercommon.core.versioning;

import org.jspecify.annotations.NullMarked;
