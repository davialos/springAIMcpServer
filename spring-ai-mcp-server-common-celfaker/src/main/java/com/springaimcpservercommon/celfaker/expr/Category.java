package com.springaimcpservercommon.celfaker.expr;

/** The CEL feature an expression exercises. */
public enum Category {
    /** {@code ==, !=, <, <=, >, >=}. */
    COMPARISON,
    /** {@code + - * / %} and unary minus. */
    ARITHMETIC,
    /** {@code &&, ||, !}. */
    LOGICAL,
    /** {@code in}. */
    MEMBERSHIP,
    /** {@code size, contains, startsWith, endsWith}, concatenation. */
    STRING_FUNCTION,
    /** {@code matches} (RE2). */
    REGEX,
    /** Timestamp and duration accessors and arithmetic. */
    TEMPORAL,
    /** Comprehension macros {@code exists, all, exists_one, map, filter}. */
    MACRO,
    /** Indexing, key tests and {@code has()}. */
    ACCESS,
    /** {@code int(), double(), string(), timestamp(), duration(), bool()}. */
    CONVERSION,
    /** The ternary {@code ?:}. */
    CONDITIONAL,
    /** {@code type()}, {@code dyn()}. */
    TYPE,
    /** An expression over two or more parameters. */
    CROSS_PARAMETER
}
