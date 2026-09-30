package com.springaimcpservercommon.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkntJsonSchemaValidationPortTest {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";

    private final NetworkntJsonSchemaValidationPort port = new NetworkntJsonSchemaValidationPort();

    @Test
    void aConformingDocumentHasNoErrors() {
        assertThat(port.validate(SCHEMA, "{\"name\":\"Alice\"}")).isEmpty();
    }

    @Test
    void aMissingRequiredPropertyAndAWrongTypeAreReported() {
        assertThat(port.validate(SCHEMA, "{\"age\":30}")).isNotEmpty();
        assertThat(port.validate(SCHEMA, "{\"name\":5}")).isNotEmpty();
    }

    @Test
    void aBrokenSchemaIsAnErrorNotASilentPass() {
        assertThatThrownBy(() -> port.validate("{not json", "{}")).isInstanceOf(IllegalArgumentException.class);
    }
}
