/**
 * JPA Criteria API implementation of the query engine (LLD-05 §4):
 * {@link com.springaimcpservercommon.query.criteria.CriteriaCompiler} maps the query AST to a
 * JPA {@code CriteriaQuery<Tuple>}, and
 * {@link com.springaimcpservercommon.query.criteria.CriteriaQueryExecutor} executes it with bulkhead
 * protection, read-only transactions, and query timeouts.
 */
@NullMarked
package com.springaimcpservercommon.query.criteria;

import org.jspecify.annotations.NullMarked;
