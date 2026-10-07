package com.springaimcpservercommon.loadtest.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoolRefTest {

    @Test
    void parsesTableColumnsWithAndWithoutSchema() {
        assertThat(PoolRef.parse("customers.id").key()).isEqualTo("customers.id");
        assertThat(PoolRef.parse("shop.customers.id").key()).isEqualTo("shop.customers.id");
        assertThat(PoolRef.parse("public.customers.id").key()).isEqualTo("customers.id");
        assertThat(PoolRef.parse("shop.customers.id").tableKeyOrNull()).isEqualTo("shop.customers");
        assertThat(PoolRef.parse("customers.id").isQuery()).isFalse();
        assertThatThrownBy(() -> PoolRef.parse("customers")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql:SELECT");
    }

    @Test
    void parsesAQueryPoolWithAStableKeyIndependentOfWhitespaceAndSemicolon() {
        PoolRef a = PoolRef.parse("sql:SELECT id FROM orders WHERE status = 'NEW';");
        PoolRef b = PoolRef.parse("SQL:  select id\n  from orders where status = 'NEW'  ".replace("select", "SELECT")
                .replace("from", "FROM").replace("where", "WHERE"));
        assertThat(a.isQuery()).isTrue();
        assertThat(a.key()).startsWith("sql:").hasSize(12);
        assertThat(a.key()).isEqualTo(b.key());
        assertThat(a.sql()).isEqualTo("SELECT id FROM orders WHERE status = 'NEW'");
        assertThat(a.tableKeyOrNull()).isNull();
        assertThat(PoolRef.parse("sql:SELECT id FROM orders").key()).isNotEqualTo(a.key());
    }

    @Test
    void acceptsWithQueriesAndColumnsThatMerelyContainAWriteWord() {
        assertThat(PoolRef.parse("sql:WITH n AS (SELECT id, update_date FROM orders) SELECT id FROM n").isQuery())
                .isTrue();
    }

    @Test
    void refusesAnythingButASingleReadOnlyQuery() {
        for (String bad : new String[]{"sql:", "sql:DELETE FROM orders", "sql:SELECT 1; DROP TABLE orders",
                "sql:SELECT * INTO copy FROM orders", "sql:SELECT id FROM orders FOR UPDATE",
                "sql:UPDATE orders SET x = 1 RETURNING id", "sql:CALL purge()"}) {
            assertThatThrownBy(() -> PoolRef.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("query pool");
        }
    }
}
