package com.springaimcpservercommon.celfaker.expr;

import java.util.List;

/**
 * A CEL expression that compiled against the parameter library.
 *
 * @param expression  CEL text (type-checks to bool)
 * @param category    the CEL feature it exercises
 * @param parameters  CEL names of the parameters it reads
 * @param description what it asserts, in words
 */
public record GeneratedExpression(String expression, Category category, List<String> parameters, String description) {
}
