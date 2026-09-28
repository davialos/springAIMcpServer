/**
 * Dynamic query engine (LLD-05, ADR-0004): structured query AST compiled to JPA Criteria API over host JPA entities,
 * with publish-time and runtime validation, row-level security (row policies and {@code @AiQueryConstraints}),
 * and a bulkhead executor.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code ast} — immutable query AST types (QueryDefinition, FilterNode, Operand, …)</li>
 *   <li>{@code validation} — publish-time and runtime validator</li>
 *   <li>{@code execution} — QueryExecutor port and QueryResult</li>
 *   <li>{@code criteria} — JPA Criteria API compiler and executor implementation</li>
 * </ul>
 */
@NullMarked
package com.springaimcpservercommon.query;

import org.jspecify.annotations.NullMarked;
