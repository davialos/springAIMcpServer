package com.springaimcpservercommon.loadtest.model;

/**
 * Shape of a request value: a closed hierarchy mirroring the subset of JSON Schema that request payloads use.
 */
public sealed interface Schema permits ScalarSchema, ArraySchema, ObjectSchema, RefSchema {
}
