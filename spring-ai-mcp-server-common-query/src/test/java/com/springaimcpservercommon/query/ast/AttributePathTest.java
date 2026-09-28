package com.springaimcpservercommon.query.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributePathTest {

    @Test
    void singleSegmentPath() {
        AttributePath p = AttributePath.of("status");
        assertThat(p.segments()).containsExactly("status");
        assertThat(p.attributeName()).isEqualTo("status");
        assertThat(p.joinPath()).isEmpty();
        assertThat(p.joinDepth()).isZero();
        assertThat(p.toString()).isEqualTo("status");
    }

    @Test
    void multiSegmentPath() {
        AttributePath p = AttributePath.of("customer", "address", "city");
        assertThat(p.attributeName()).isEqualTo("city");
        assertThat(p.joinPath()).containsExactly("customer", "address");
        assertThat(p.joinDepth()).isEqualTo(2);
        assertThat(p.toString()).isEqualTo("customer.address.city");
    }

    @Test
    void parseDotPath() {
        AttributePath p = AttributePath.parse("order.item.sku");
        assertThat(p.segments()).containsExactly("order", "item", "sku");
    }

    @Test
    void emptySegmentsThrows() {
        assertThatThrownBy(() -> new AttributePath(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segmentsAreImmutable() {
        AttributePath p = AttributePath.of("a");
        assertThatThrownBy(() -> ((java.util.List<String>) p.segments()).add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
