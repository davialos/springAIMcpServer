package com.springaimcpservercommon.loadtest.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserDataTest {

    @Test
    void loadsJsonWithFieldsPayloadsAndBindings(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("values.json");
        Files.writeString(f, """
                {"fields": {"email": ["a@example.com"], "createOrder.body.customerId": [1, 2], "price": 9.5},
                 "payloads": {"createOrder": {"customerId": 1}},
                 "bindings": {"*.customerId": "customers.id"}}
                """);
        UserData u = UserData.load(f);
        assertThat(u.fields().get("createOrder.body.customerId")).containsExactly(1L, 2L);
        assertThat(u.fields().get("price")).containsExactly(new BigDecimal("9.5"));
        assertThat(u.payloads().get("createOrder")).hasSize(1);
        assertThat(u.bindings()).containsEntry("*.customerId", "customers.id");
    }

    @Test
    void loadsCsvColumnsAsFieldValuesSkippingEmptyCells(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("values.csv");
        Files.writeString(f, "email,CreateOrderRequest.customerId,notes\r\n\"x@example.com\",7,\"a, \"\"quoted\"\" note\"\n,8,\n");
        UserData u = UserData.load(f);
        assertThat(u.fields().get("email")).containsExactly("x@example.com");
        assertThat(u.fields().get("CreateOrderRequest.customerId")).containsExactly(7L, 8L);
        assertThat(u.fields().get("notes")).containsExactly("a, \"quoted\" note");
    }

    @Test
    void lookupPrefersTheExactKeyThenOwnerThenBareName() {
        UserData u = new UserData(Map.of("email", List.of("bare"), "CreateCustomerRequest.email", List.of("owner")),
                Map.of(), Map.of());
        assertThat(u.valuesFor("CreateCustomerRequest.email", "CreateCustomerRequest")).containsExactly("owner");
        assertThat(u.valuesFor("listCustomers.query.email", "listCustomers")).containsExactly("bare");
        assertThat(u.merge(u).fields().get("email")).containsExactly("bare");
        assertThat(u.merge(new UserData(Map.of("email", List.of("new")), Map.of(), Map.of())).fields().get("email"))
                .containsExactly("bare", "new");
    }
}
