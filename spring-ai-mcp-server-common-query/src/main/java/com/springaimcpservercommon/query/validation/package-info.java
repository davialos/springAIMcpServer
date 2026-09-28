/**
 * Query validation (LLD-05 §3): publish-time and runtime validation of {@link com.springaimcpservercommon.query.ast.QueryDefinition}
 * against the effective catalog — path existence, sensitivity, operator compatibility, IN-list limits,
 * classification clearance, mandatory filter coverage, and join depth.
 */
@NullMarked
package com.springaimcpservercommon.query.validation;

import org.jspecify.annotations.NullMarked;
