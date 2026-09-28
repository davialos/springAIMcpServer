package com.springaimcpservercommon.query.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterNodeTest {

    @Test
    void andRequiresAtLeastOneChild() {
        assertThatThrownBy(() -> new FilterNode.And(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orRequiresAtLeastOneChild() {
        assertThatThrownBy(() -> new FilterNode.Or(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparisonPreservesFields() {
        AttributePath path = AttributePath.of("status");
        var node = new FilterNode.Comparison(path, Operator.EQ, new Operand.Literal("ACTIVE"));
        assertThat(node.path()).isEqualTo(path);
        assertThat(node.op()).isEqualTo(Operator.EQ);
        assertThat(node.operand()).isEqualTo(new Operand.Literal("ACTIVE"));
    }

    @Test
    void andChildrenAreImmutable() {
        FilterNode.Comparison leaf = new FilterNode.Comparison(
                AttributePath.of("x"), Operator.IS_NULL, new Operand.Literal(null));
        FilterNode.And and = new FilterNode.And(List.of(leaf));
        assertThatThrownBy(() -> ((java.util.List<?>) and.children()).add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sealedHierarchyPatternMatchCoversAllPermits() {
        FilterNode.Comparison leaf = new FilterNode.Comparison(
                AttributePath.of("a"), Operator.EQ, new Operand.Literal(1));
        FilterNode node = new FilterNode.And(List.of(new FilterNode.Or(List.of(new FilterNode.Not(leaf)))));
        String result = switch (node) {
            case FilterNode.And ignored -> "and";
            case FilterNode.Or ignored -> "or";
            case FilterNode.Not ignored -> "not";
            case FilterNode.Comparison ignored -> "comparison";
        };
        assertThat(result).isEqualTo("and");
    }
}
