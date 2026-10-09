package com.springaimcpservercommon.celfaker.workflow;

import java.util.Set;

/**
 * A check on a step's response.
 *
 * @param from  {@code body.<path>}, {@code header.<Name>} or {@code status} (same grammar as {@link Extract#from()})
 * @param op    {@code ==}, {@code !=}, {@code exists}, {@code absent}, {@code contains}, {@code >}, {@code >=}, {@code <},
 *              {@code <=} or {@code matches} (regular expression)
 * @param value expected value as text; may use {@code {{step.var}}} placeholders; ignored by {@code exists} / {@code absent}
 */
public record Assertion(String from, String op, String value) {

    /** Operators the runtime understands. */
    public static final Set<String> OPS = Set.of("==", "!=", "exists", "absent", "contains", ">", ">=", "<", "<=", "matches");

    /** Normalises omitted fields. */
    public Assertion {
        value = value == null ? "" : value;
    }
}
