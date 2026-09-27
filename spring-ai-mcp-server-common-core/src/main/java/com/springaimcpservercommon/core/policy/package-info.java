/**
 * Policy resolution chain (LLD-03 §4): policy JSON documents (schema version 1), their strict parser, layer inputs
 * and the {@link com.springaimcpservercommon.core.policy.PolicyMerger} that turns a scanned catalog plus ordered
 * layers into an effective catalog. Only code exposes; layers can only disable, restrict or re-describe; invalid
 * layers fail closed.
 */
@NullMarked
package com.springaimcpservercommon.core.policy;

import org.jspecify.annotations.NullMarked;
