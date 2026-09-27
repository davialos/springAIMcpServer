/**
 * Ports through which the security module reads the {@code dynamic_ai} store (principals, role mappings, memberships,
 * grants, API keys, kill switches, resource status, approved MCP clients). They are implemented by autoconfigure on
 * top of the persistence stores, so this module has no dependency on JPA or the persistence module.
 */
@NullMarked
package com.springaimcpservercommon.security.port;

import org.jspecify.annotations.NullMarked;
