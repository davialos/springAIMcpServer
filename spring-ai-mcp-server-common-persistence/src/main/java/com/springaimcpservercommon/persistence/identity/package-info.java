/**
 * Identity references, workspaces, IAM role mappings, service accounts, API keys and approved MCP clients
 * (SEC-01, V1/V2 migrations).
 *
 * <p>Identity itself is owned by the host's IdP (ADR-0005): {@code dai_principal} only references external subjects
 * so that other tables can use a compact foreign key. Stores in this package are plain classes that receive a
 * {@link com.springaimcpservercommon.persistence.unit.DaiStore} through their constructor; they return immutable
 * view records, never managed entities.
 */
@NullMarked
package com.springaimcpservercommon.persistence.identity;

import org.jspecify.annotations.NullMarked;
