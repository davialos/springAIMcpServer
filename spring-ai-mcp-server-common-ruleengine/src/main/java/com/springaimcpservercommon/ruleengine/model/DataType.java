package com.springaimcpservercommon.ruleengine.model;

/** Declared type of a parameter library attribute. */
public enum DataType {
    /** CEL string. */
    STRING,
    /** CEL int (64-bit). */
    INT,
    /** CEL double. */
    DOUBLE,
    /** CEL bool. */
    BOOL,
    /** CEL timestamp ({@link java.time.Instant} or an ISO-8601 string). */
    TIMESTAMP,
    /** CEL duration ({@link java.time.Duration} or an ISO-8601 string). */
    DURATION,
    /** List of strings. */
    LIST_STRING,
    /** List of ints. */
    LIST_INT,
    /** List of doubles. */
    LIST_DOUBLE,
    /** Map with string keys and dynamic values. */
    MAP,
    /** Untyped (dyn). */
    ANY
}
