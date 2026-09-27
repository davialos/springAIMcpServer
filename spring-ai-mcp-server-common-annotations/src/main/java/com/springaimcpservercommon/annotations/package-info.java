/**
 * Runtime-retained semantic annotations that host developers put on their entities, services and actions so
 * that AI agents and the MCP server understand what the host application means (LLD-02, ADR-0013).
 *
 * <p>Nothing is visible to AI unless it is annotated; nothing becomes writable unless a developer says so.
 * This module has no dependencies so it can live in shared domain or API jars.
 */
package com.springaimcpservercommon.annotations;
