/**
 * Isolated {@code dynamic_ai} persistence unit: programmatic Flyway, entity manager factory and transaction manager
 * that are never Spring beans (ADR-0019), plus the store environment guard (LLD-12 §3).
 */
@NullMarked
package com.springaimcpservercommon.persistence.unit;

import org.jspecify.annotations.NullMarked;
