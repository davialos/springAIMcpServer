package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.mcp.server.McpRequestRecorder;
import com.springaimcpservercommon.persistence.telemetry.McpRequest;
import com.springaimcpservercommon.persistence.telemetry.McpRequestStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StoreMcpRequestRecorderTest {

    @Test
    void everyStatusMapsToTheStoresEnumAndPassesEntityValidation() {
        Instant t = Instant.parse("2026-09-29T10:00:00Z");
        for (McpRequestRecorder.Status status : McpRequestRecorder.Status.values()) {
            var call = new McpRequestRecorder.McpCall(UUID.randomUUID(), t, t.plusMillis(5), UUID.randomUUID(),
                    UUID.randomUUID(), null, "tools/call", "7", "find_orders", status, null, null);
            var row = StoreMcpRequestRecorder.toRow(call);

            assertThat(row.status()).isEqualTo(McpRequestStatus.valueOf(status.name()));
            assertThat(McpRequest.of(row).getId()).isEqualTo(call.id());
        }
    }
}
