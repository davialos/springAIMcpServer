package com.springaimcpservercommon.core.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogElementRefTest {

    @Test
    void roundTripsOperationRef() {
        CatalogElementRef ref = CatalogElementRef.operation("com.acme.OrderService", "findRecent",
                List.of("java.lang.Long", "int"));
        assertThat(ref.toString()).isEqualTo("op:com.acme.OrderService#findRecent(java.lang.Long,int)");
        assertThat(CatalogElementRef.parse(ref.toString())).isEqualTo(ref);
    }

    @Test
    void rejectsUnknownKind() {
        assertThatThrownBy(() -> CatalogElementRef.parse("table:users")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInjectionCharacters() {
        assertThatThrownBy(() -> CatalogElementRef.parse("entity:com.acme.Order'; drop table x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
