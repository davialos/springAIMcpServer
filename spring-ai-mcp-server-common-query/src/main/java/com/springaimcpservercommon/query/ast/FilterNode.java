package com.springaimcpservercommon.query.ast;

import java.util.List;
import java.util.Objects;

/**
 * Immutable predicate tree for dynamic query WHERE clauses and row policies (LLD-05 §2, §5).
 *
 * <p>The tree is built from three logical combinators ({@link And}, {@link Or}, {@link Not}) and
 * leaf {@link Comparison} nodes. The compiler maps each node to a JPA {@code Predicate}.
 */
public sealed interface FilterNode permits FilterNode.And, FilterNode.Or, FilterNode.Not, FilterNode.Comparison {

    /**
     * Logical conjunction — all children must be true.
     *
     * @param children child nodes, non-empty
     */
    record And(List<FilterNode> children) implements FilterNode {
        /** Validates and copies the children list. */
        public And {
            Objects.requireNonNull(children, "children");
            if (children.isEmpty()) {
                throw new IllegalArgumentException("And must have at least one child");
            }
            children = List.copyOf(children);
        }
    }

    /**
     * Logical disjunction — at least one child must be true.
     *
     * @param children child nodes, non-empty
     */
    record Or(List<FilterNode> children) implements FilterNode {
        /** Validates and copies the children list. */
        public Or {
            Objects.requireNonNull(children, "children");
            if (children.isEmpty()) {
                throw new IllegalArgumentException("Or must have at least one child");
            }
            children = List.copyOf(children);
        }
    }

    /**
     * Logical negation.
     *
     * @param child the negated node
     */
    record Not(FilterNode child) implements FilterNode {
        /** Validates the child. */
        public Not {
            Objects.requireNonNull(child, "child");
        }
    }

    /**
     * A leaf comparison between an attribute path and an operand.
     *
     * <p>For {@link Operator#isUnary()} operators the {@code operand} is ignored; convention is to use
     * {@link Operand.Literal}{@code (null)}.
     *
     * @param path    attribute path on the root entity (joined if multi-segment)
     * @param op      comparison operator
     * @param operand right-hand side value
     */
    record Comparison(AttributePath path, Operator op, Operand operand) implements FilterNode {
        /** Validates the components. */
        public Comparison {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(operand, "operand");
        }
    }
}
